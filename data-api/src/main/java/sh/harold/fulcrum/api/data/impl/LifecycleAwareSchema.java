package sh.harold.fulcrum.api.data.impl;

import java.util.UUID;

public interface LifecycleAwareSchema {
    default void onJoin(UUID playerId) {
    }

    default void onQuit(UUID playerId) {
    }
}
