package sh.harold.fulcrum.playerdata;

import java.util.UUID;

public interface LifecycleAwareSchema {
    default void onJoin(UUID playerId) {}
    default void onQuit(UUID playerId) {}
}
