package sh.harold.fulcrum.npc.adapter;

import java.util.concurrent.CompletionStage;

/**
 * Abstraction over the underlying NPC implementation (Citizens2, etc.).
 */
public interface NpcAdapter {
    CompletionStage<NpcHandle> spawn(NpcSpawnRequest request);

    void despawn(NpcHandle handle);
}
