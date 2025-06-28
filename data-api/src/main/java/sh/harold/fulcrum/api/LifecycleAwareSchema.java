package sh.harold.fulcrum.api;

import java.util.UUID;

public interface LifecycleAwareSchema {
    default void onJoin(UUID playerId) {
    }

    default void onQuit(UUID playerId) {
    }
}
