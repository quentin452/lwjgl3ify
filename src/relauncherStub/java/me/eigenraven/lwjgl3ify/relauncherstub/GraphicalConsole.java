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
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;

import com.github.weisj.darklaf.LafManager;
import com.github.weisj.darklaf.theme.spec.ColorToneRule;
import com.github.weisj.darklaf.theme.spec.ContrastRule;
import com.github.weisj.darklaf.theme.spec.PreferredThemeStyle;

public class GraphicalConsole {

    // Give up after 32MB of logs
    static final long MAX_LOG_SIZE = 32 * 1024 * 1024;
    final ConcurrentLinkedQueue<String> consoleBuffer = new ConcurrentLinkedQueue<>();
    final AtomicLong logSize = new AtomicLong(0);
    final AtomicBoolean logSizeExceededMax = new AtomicBoolean(false);
    final InputStream stdout, stderr;
    Thread stdoutAdapter;
    Thread stderrAdapter;
    final Process process;
    final static String LINE_SEPARATOR = System.lineSeparator();

    class StreamToQueueAdapter implements Runnable {

        final BufferedReader reader;
        final JTextArea guiLog;

        StreamToQueueAdapter(InputStream stream, JTextArea guiLog) {
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            this.guiLog = guiLog;
        }

           @Override
        public void run() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    long currSize = logSize.addAndGet(line.length() + 1);
                    if (currSize > MAX_LOG_SIZE) {
                        if (!logSizeExceededMax.getAndSet(true)) {
                            invokeOnSwingThread(false, () -> {
                                guiLog.setText(""); // clear previous logs
                                try {
                                    guiLog.getDocument().insertString(
                                        guiLog.getDocument().getLength(),
                                        "Max console size exceeded, logs cleared!" + LINE_SEPARATOR,
                                        null
                                    );
                                } catch (BadLocationException ignored) {}
                            });
                            logSize.set(line.length() + 1); // reset log size to current line
                        }
                    }
                    // Write current line to GUI
                    final String logLine = line;
                    invokeOnSwingThread(false, () -> {
                        try {
                            guiLog.getDocument().insertString(guiLog.getDocument().getLength(), logLine + LINE_SEPARATOR, null);
                        } catch (BadLocationException ignored) {}
                    });
                }
            } catch (IOException ignored) {}
            finally {
                try { reader.close(); } catch (IOException ignored) {}
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
        } catch (Exception ignored) {}

        invokeOnSwingThread(true, () -> {
            final JFrame consoleWindow = new JFrame("Lwjgl3ify relaunch console");
            consoleWindow.setMinimumSize(new Dimension(640, 500));
            consoleWindow.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            final JTextArea logArea = new JTextArea();
            logArea.setEditable(false);
            logArea.setFont(Font.decode(Font.MONOSPACED).deriveFont(14f));
            try {
                logArea.getDocument().insertString(
                    logArea.getDocument().getLength(),
                    "Relaunching the process with new Java..." + System.lineSeparator(),
                    null
                );
            } catch (BadLocationException ignored) {}

            final JScrollPane logScroll = new JScrollPane(logArea);
            consoleWindow.getContentPane().add(logScroll, BorderLayout.CENTER);

            stdoutAdapter = new Thread(new StreamToQueueAdapter(stdout, logArea), "stdout adapter");
            stdoutAdapter.setDaemon(true);
            stdoutAdapter.start();
            stderrAdapter = new Thread(new StreamToQueueAdapter(stderr, logArea), "stderr adapter");
            stderrAdapter.setDaemon(true);
            stderrAdapter.start();

            final JPanel buttonPanel = new JPanel();
            buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
            final JButton closeMeButton = new JButton("Close console");
            closeMeButton.addActionListener(al -> consoleWindow.dispose());
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
                        try {
                            logArea.getDocument().insertString(logArea.getDocument().getLength(),
                                    "Process exited with code " + exitCode + LINE_SEPARATOR, null);
                        } catch (BadLocationException ignored) {}
                    });
                } catch (InterruptedException ignored) {}
            }, "death awaiter");
            processDeathAwaiter.start();
            consoleWindow.setVisible(true);
        });
    }

    private void invokeOnSwingThread(boolean wait, Runnable runnable) {
        try {
            if (wait) SwingUtilities.invokeAndWait(runnable);
            else SwingUtilities.invokeLater(runnable);
        } catch (InterruptedException ignored) {}
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            else throw new RuntimeException(e);
        }
    }
}
