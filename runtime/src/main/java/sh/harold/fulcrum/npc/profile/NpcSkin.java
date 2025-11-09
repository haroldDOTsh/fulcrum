package sh.harold.fulcrum.npc.profile;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Describes how an NPC skin should be resolved.
 */
public final class NpcSkin {

    private final Source source;
    private final UUID profileId;
    private final String username;
    private final String textureValue;
    private final String textureSignature;
    private final String externalUrl;

    private NpcSkin(Source source,
                    UUID profileId,
                    String username,
                    String textureValue,
                    String textureSignature,
                    String externalUrl) {
        this.source = source;
        this.profileId = profileId;
        this.username = username;
        this.textureValue = textureValue;
        this.textureSignature = textureSignature;
        this.externalUrl = externalUrl;
    }

    public static NpcSkin fromMojangProfile(UUID profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return new NpcSkin(Source.MOJANG_UUID, profileId, null, null, null, null);
    }

    public static NpcSkin fromMojangUsername(String username) {
        String trimmed = sanitize(username);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Mojang username is required");
        }
        return new NpcSkin(Source.MOJANG_USERNAME, null, trimmed, null, null, null);
    }

    public static NpcSkin fromExternalUrl(String url) {
        String normalized = sanitize(url);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("External skin URL is required");
        }
        try {
            new URL(normalized);
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Invalid external skin URL: " + url, exception);
        }
        return new NpcSkin(Source.EXTERNAL_URL, null, null, null, null, normalized);
    }

    public static NpcSkin fromTexturePayload(String textureValue, String textureSignature) {
        String value = sanitize(textureValue);
        String signature = sanitize(textureSignature);
        if (value.isEmpty() || signature.isEmpty()) {
            throw new IllegalArgumentException("Texture payload and signature are required");
        }
        return new NpcSkin(Source.TEXTURE_PAYLOAD, null, null, value, signature, null);
    }

    private static String sanitize(String input) {
        return input == null ? "" : input.trim();
    }

    public Source source() {
        return source;
    }

    public Optional<UUID> profileId() {
        return Optional.ofNullable(profileId);
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public Optional<String> textureValue() {
        return Optional.ofNullable(textureValue);
    }

    public Optional<String> textureSignature() {
        return Optional.ofNullable(textureSignature);
    }

    public Optional<String> externalUrl() {
        return Optional.ofNullable(externalUrl);
    }

    public boolean isResolved() {
        return source == Source.TEXTURE_PAYLOAD;
    }

    public enum Source {
        TEXTURE_PAYLOAD,
        MOJANG_UUID,
        MOJANG_USERNAME,
        EXTERNAL_URL
    }
}
