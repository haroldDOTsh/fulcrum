package sh.harold.fulcrum.npc.skin;

/**
 * Resolved skin data ready to hand to Citizens.
 */
public record NpcSkinPayload(String textureValue, String textureSignature) {
    public NpcSkinPayload {
        if (textureValue == null || textureValue.isBlank()) {
            throw new IllegalArgumentException("textureValue is required");
        }
        if (textureSignature == null || textureSignature.isBlank()) {
            throw new IllegalArgumentException("textureSignature is required");
        }
    }
}
