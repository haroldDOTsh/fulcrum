package sh.harold.fulcrum.npc.poi;

/**
 * Listener that reacts to POI activation lifecycle events.
 */
public interface PoiActivationListener {
    default void onActivated(PoiActivatedEvent event) {
    }

    default void onDeactivated(PoiDeactivatedEvent event) {
    }
}
