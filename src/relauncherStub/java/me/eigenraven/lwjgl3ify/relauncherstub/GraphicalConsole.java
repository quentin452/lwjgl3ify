package me.eigenraven.lwjgl3ify.relauncherstub;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;

import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.spec.ColorToneRule;
import com.github.weisj.darklaf.theme.spec.ContrastRule;
import com.github.weisj.darklaf.theme.spec.PreferredThemeStyle;

public class GraphicalConsole {

    // Give up after 32MB of logs
    static final long MAX_LOG_SIZE = 32 * 1024 * 1024;
    final ConcurrentLinkedQueue<String> pendingLines = new ConcurrentLinkedQueue<>();
    final AtomicLong logSize = new AtomicLong(0);
    final AtomicBoolean logSizeExceededMax = new AtomicBoolean(false);
    final InputStream stdout, stderr;
    Thread stdoutAdapter;
    Thread stderrAdapter;
    final Process process;
    final static String LINE_SEPARATOR = System.lineSeparator();
    
    // Batch update properties
    private static final int BATCH_UPDATE_DELAY_MS = 50; // Update GUI every 50ms max
    private static final int MAX_BATCH_LINES = 100; // Max lines to process per update
    private Timer updateTimer;
    private JTextArea logArea;

    class StreamToQueueAdapter implements Runnable {

        final BufferedReader reader;

        StreamToQueueAdapter(InputStream stream) {
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    long lineLength = line.length() + LINE_SEPARATOR.length();
                    long currSize = logSize.addAndGet(lineLength);
                    
                    if (currSize > MAX_LOG_SIZE) {
                        if (!logSizeExceededMax.getAndSet(true)) {
                            // Queue the clear message, will be handled by the batch processor
                            pendingLines.clear(); // Clear pending lines
                            pendingLines.offer("[MAX CONSOLE SIZE EXCEEDED, LOGS CLEARED]");
                            logSize.set(lineLength); // Reset log size to current line
                        }
                    }
                    
                    // Add line to queue for batch processing
                    pendingLines.offer(line);
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public GraphicalConsole(InputStream stdout, InputStream stderr, Process process) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.process = process;

        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            LafManager.installTheme(new PreferredThemeStyle(ContrastRule.STANDARD, ColorToneRule.DARK));
        } catch (Exception ignored) {
        }

        invokeOnSwingThread(true, () -> {
            final JFrame consoleWindow = new JFrame("Lwjgl3ify relaunch console");
            consoleWindow.setMinimumSize(new Dimension(640, 500));
            consoleWindow.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            logArea = new JTextArea();
            logArea.setEditable(false);
            logArea.setFont(Font.decode(Font.MONOSPACED).deriveFont(14f));
            try {
                logArea.getDocument().insertString(
                    logArea.getDocument().getLength(),
                    "Relaunching the process with new Java..." + LINE_SEPARATOR,
                    null
                );
            } catch (BadLocationException ignored) {
            }

            final JScrollPane logScroll = new JScrollPane(logArea);
            consoleWindow.getContentPane().add(logScroll, BorderLayout.CENTER);

            // Setup batch update timer
            updateTimer = new Timer(BATCH_UPDATE_DELAY_MS, e -> processPendingLines());
            updateTimer.setRepeats(true);
            updateTimer.start();

            stdoutAdapter = new Thread(new StreamToQueueAdapter(stdout), "stdout adapter");
            stdoutAdapter.setDaemon(true);
            stdoutAdapter.start();
            stderrAdapter = new Thread(new StreamToQueueAdapter(stderr), "stderr adapter");
            stderrAdapter.setDaemon(true);
            stderrAdapter.start();

            final JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
            final JButton closeMeButton = new JButton("Close console");
            closeMeButton.addActionListener(al -> {
                if (updateTimer != null) {
                    updateTimer.stop();
                }
                consoleWindow.dispose();
            });
            final JButton killButton = new JButton("Kill process");
            killButton.addActionListener(al -> process.destroyForcibly());
            buttonPanel.add(closeMeButton);
            buttonPanel.add(killButton);
            consoleWindow.getContentPane().add(buttonPanel, BorderLayout.SOUTH);

            final Thread processDeathAwaiter = new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    invokeOnSwingThread(false, () -> {
                        killButton.setEnabled(false);
                        // Process any remaining lines before showing exit message
                        processPendingLines();
                        try {
                            logArea.getDocument().insertString(logArea.getDocument().getLength(),
                                    "Process exited with code " + exitCode + LINE_SEPARATOR, null);
                        } catch (BadLocationException ignored) {
                        }
                    });
                } catch (InterruptedException ignored) {
                }
            }, "death awaiter");
            processDeathAwaiter.setDaemon(true);
            processDeathAwaiter.start();
            
            // Stop timer when window closes
            consoleWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    if (updateTimer != null) {
                        updateTimer.stop();
                    }
                }
            });
            
            consoleWindow.setVisible(true);
        });
    }

    private void processPendingLines() {
        if (pendingLines.isEmpty()) {
            return;
        }

        List<String> linesToAdd = new ArrayList<>(MAX_BATCH_LINES);
        String line;
        int lineCount = 0;
        
        // Batch lines for efficient document update
        while ((line = pendingLines.poll()) != null && lineCount < MAX_BATCH_LINES) {
            if ("[MAX CONSOLE SIZE EXCEEDED, LOGS CLEARED]".equals(line)) {
                logArea.setText("");
                try {
                    logArea.getDocument().insertString(0, "Max console size exceeded, logs cleared!" + LINE_SEPARATOR, null);
                } catch (BadLocationException ignored) {
                }
                continue;
            }
            linesToAdd.add(line);
            lineCount++;
        }

        if (!linesToAdd.isEmpty()) {
            StringBuilder batch = new StringBuilder();
            for (String l : linesToAdd) {
                batch.append(l).append(LINE_SEPARATOR);
            }

            try {
                // Single document update for all batched lines
                logArea.getDocument().insertString(logArea.getDocument().getLength(), batch.toString(), null);
                
                // Auto-scroll to bottom
                logArea.setCaretPosition(logArea.getDocument().getLength());
            } catch (BadLocationException ignored) {
            }
        }

        // If we hit the batch limit, schedule another update immediately
        if (lineCount >= MAX_BATCH_LINES) {
            SwingUtilities.invokeLater(this::processPendingLines);
        }
    }

    private void invokeOnSwingThread(boolean wait, Runnable runnable) {
        try {
            if (wait) {
                SwingUtilities.invokeAndWait(runnable);
            } else {
                SwingUtilities.invokeLater(runnable);
            }
        } catch (InterruptedException ignored) {
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw new RuntimeException(e);
            }
        }
    }
}