package sh.harold.fulcrum.host.tick;

@FunctionalInterface
public interface HostEventListener<H> {
    void onHostEvent(H hostEvent);
}
