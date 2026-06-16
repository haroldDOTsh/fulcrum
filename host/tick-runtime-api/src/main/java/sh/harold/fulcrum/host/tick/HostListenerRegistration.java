package sh.harold.fulcrum.host.tick;

public interface HostListenerRegistration extends AutoCloseable {
    @Override
    void close();
}
