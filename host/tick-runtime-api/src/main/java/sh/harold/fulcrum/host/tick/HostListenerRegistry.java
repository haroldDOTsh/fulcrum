package sh.harold.fulcrum.host.tick;

public interface HostListenerRegistry {
    <H> HostListenerRegistration register(String listenerKey, Class<H> eventType, HostEventListener<? super H> listener);
}
