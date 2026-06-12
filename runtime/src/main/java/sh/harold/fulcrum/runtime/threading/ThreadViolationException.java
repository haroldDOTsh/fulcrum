package sh.harold.fulcrum.runtime.threading;

public class ThreadViolationException extends RuntimeException {
    public ThreadViolationException(String message) {
        super(message);
    }
}
