package sh.harold.fulcrum.npc.poi;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory implementation of {@link PoiActivationBus}.
 */
public final class SimplePoiActivationBus implements PoiActivationBus {
    private final List<PoiActivationListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public Subscription subscribe(PoiActivationListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void publishActivated(PoiActivatedEvent event) {
        for (PoiActivationListener listener : listeners) {
            try {
                listener.onActivated(event);
            } catch (Exception exception) {
                // Deliberately swallow to avoid destabilising the bus; logging occurs at caller sites.
            }
        }
    }

    @Override
    public void publishDeactivated(PoiDeactivatedEvent event) {
        for (PoiActivationListener listener : listeners) {
            try {
                listener.onDeactivated(event);
            } catch (Exception ignored) {
            }
        }
    }
}
