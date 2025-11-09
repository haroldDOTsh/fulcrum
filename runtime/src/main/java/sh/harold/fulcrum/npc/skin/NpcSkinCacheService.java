package sh.harold.fulcrum.npc.skin;

import sh.harold.fulcrum.npc.profile.NpcProfile;

import java.util.concurrent.CompletionStage;

/**
 * Resolves NPC skin descriptors into signed payloads.
 */
public interface NpcSkinCacheService {
    CompletionStage<NpcSkinPayload> resolve(NpcProfile profile);
}
