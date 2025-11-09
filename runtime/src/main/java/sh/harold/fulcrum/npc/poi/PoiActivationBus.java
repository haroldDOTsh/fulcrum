package sh.harold.fulcrum.npc.poi;

/**
 * Simple event bus for POI lifecycle events.
 */
public interface PoiActivationBus {
    Subscription subscribe(PoiActivationListener listener);

    void publishActivated(PoiActivatedEvent event);

    void publishDeactivated(PoiDeactivatedEvent event);

    interface Subscription extends AutoCloseable {
        @Override
        default void close() {
            unsubscribe();
        }

        void unsubscribe();
    }
}
