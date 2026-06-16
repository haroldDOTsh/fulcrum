package sh.harold.fulcrum.host.tick;

public interface HostMainThread {
    boolean isMainThread();

    void execute(Runnable task);
}
