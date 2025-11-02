package sh.harold.fulcrum.registry.console;

import java.io.PrintStream;
import java.util.Objects;

/**
 * PrintStream wrapper that keeps an interactive prompt visible by re-printing it after log lines.
 */
public final class PromptAwarePrintStream extends PrintStream {
    private final PrintStream delegate;
    private final Object lock = new Object();

    private volatile boolean promptActive;
    private volatile String prompt = "";
    private boolean promptPrinted;
    private boolean writingPrompt;
    private boolean restoringPrompt;

    public PromptAwarePrintStream(PrintStream delegate) {
        super(Objects.requireNonNull(delegate), true);
        this.delegate = delegate;
    }

    public void activatePrompt(String prompt) {
        synchronized (lock) {
            this.prompt = prompt;
            this.promptActive = true;
            this.promptPrinted = false;
        }
    }

    public void deactivatePrompt() {
        synchronized (lock) {
            this.promptActive = false;
        }
    }

    public PrintStream getDelegate() {
        return delegate;
    }

    @Override
    public void write(int b) {
        synchronized (lock) {
            ensureLineClear();
            super.write(b);
            if (shouldRestorePromptForByte(b)) {
                restorePrompt();
            }
        }
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        if (len == 0) {
            return;
        }

        synchronized (lock) {
            ensureLineClear();
            super.write(buf, off, len);
            if (shouldRestorePromptForBuffer(buf, off, len)) {
                restorePrompt();
            }
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            super.flush();
        }
    }

    public void printPrompt() {
        synchronized (lock) {
            writingPrompt = true;
            delegate.print(prompt);
            delegate.flush();
            writingPrompt = false;
            promptPrinted = true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            super.flush();
        }
    }

    private boolean shouldRestorePromptForByte(int value) {
        return promptActive && value == '\n';
    }

    private boolean shouldRestorePromptForBuffer(byte[] buf, int off, int len) {
        if (!promptActive) {
            return false;
        }
        int lastIndex = off + len - 1;
        return buf[lastIndex] == '\n';
    }

    private void restorePrompt() {
        if (!promptActive) {
            return;
        }

        super.flush();
        restoringPrompt = true;
        try {
            writingPrompt = true;
            delegate.print(prompt);
            delegate.flush();
            promptPrinted = true;
        } finally {
            writingPrompt = false;
            restoringPrompt = false;
        }
    }

    private void ensureLineClear() {
        if (!promptActive || !promptPrinted || writingPrompt || restoringPrompt) {
            return;
        }

        delegate.println();
        delegate.flush();
        promptPrinted = false;
    }
}
