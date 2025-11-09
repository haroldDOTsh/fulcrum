package sh.harold.fulcrum.npc.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sh.harold.fulcrum.npc.profile.NpcProfile;
import sh.harold.fulcrum.npc.profile.NpcSkin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves NPC skins through MineSkin (for URLs) and Mojang session servers (for usernames/UUIDs).
 * Successful responses are cached on disk to avoid re-requesting large payloads.
 */
public final class HttpNpcSkinCacheService implements NpcSkinCacheService {
    private static final String MINESKIN_ENDPOINT = "https://api.mineskin.org/generate/url";
    private static final URI MOJANG_PROFILE_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/");
    private static final URI MOJANG_USERNAME_ENDPOINT = URI.create("https://api.mojang.com/users/profiles/minecraft/");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final HexFormat HEX = HexFormat.of();
    private final Logger logger;
    private final Executor executor;
    private final Path cacheDirectory;
    private final HttpClient httpClient;
    private final Map<String, CompletableFuture<NpcSkinPayload>> inflight = new ConcurrentHashMap<>();

    public HttpNpcSkinCacheService(Logger logger,
                                   Executor executor,
                                   Path cacheDirectory) {
        this.logger = logger;
        this.executor = executor;
        this.cacheDirectory = cacheDirectory;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        try {
            Files.createDirectories(cacheDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create NPC skin cache directory", exception);
        }
    }

    private static NpcSkinPayload toPayload(JsonObject texture) {
        String value = texture.get("value").getAsString();
        String signature = texture.get("signature").getAsString();
        return new NpcSkinPayload(value, signature);
    }

    private static NpcSkinPayload fromPayload(NpcSkin skin) {
        String value = skin.textureValue().orElseThrow(() -> new IllegalStateException("Texture value missing"));
        String signature = skin.textureSignature().orElseThrow(() -> new IllegalStateException("Texture signature missing"));
        return new NpcSkinPayload(value, signature);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sanitize(String input) {
        return input == null ? "" : input.trim();
    }

    private static String cleanUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String formatUuid(String raw) {
        if (raw.contains("-")) {
            return raw;
        }
        return raw.replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5");
    }

    @Override
    public CompletionStage<NpcSkinPayload> resolve(NpcProfile profile) {
        NpcSkin skin = profile.skin();
        if (skin.source() == NpcSkin.Source.TEXTURE_PAYLOAD) {
            return CompletableFuture.completedFuture(fromPayload(skin));
        }

        String cacheKey = cacheKeyFor(skin);
        // Collapse duplicate fetches for the same descriptor key.
        return inflight.computeIfAbsent(cacheKey, key ->
                CompletableFuture.supplyAsync(() -> resolveSkinWithCache(key, skin), executor)
                        .whenComplete((result, throwable) -> inflight.remove(key)));
    }

    private NpcSkinPayload resolveSkinWithCache(String cacheKey, NpcSkin descriptor) {
        Optional<NpcSkinPayload> cached = readCache(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        NpcSkinPayload payload = fetchRemote(descriptor);
        writeCache(cacheKey, payload);
        return payload;
    }

    private Optional<NpcSkinPayload> readCache(String cacheKey) {
        Path file = cacheDirectory.resolve(cacheKey + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String value = root.get("value").getAsString();
            String signature = root.get("signature").getAsString();
            return Optional.of(new NpcSkinPayload(value, signature));
        } catch (Exception exception) {
            logger.log(Level.WARNING, "Failed to read NPC skin cache entry " + file, exception);
            return Optional.empty();
        }
    }

    private void writeCache(String cacheKey, NpcSkinPayload payload) {
        Path file = cacheDirectory.resolve(cacheKey + ".json");
        JsonObject root = new JsonObject();
        root.addProperty("value", payload.textureValue());
        root.addProperty("signature", payload.textureSignature());
        try {
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Failed to write NPC skin cache entry " + file, exception);
        }
    }

    private NpcSkinPayload fetchRemote(NpcSkin descriptor) {
        try {
            return switch (descriptor.source()) {
                case MOJANG_UUID -> fetchFromUuid(descriptor.profileId()
                        .orElseThrow(() -> new IllegalArgumentException("UUID missing for skin descriptor")));
                case MOJANG_USERNAME -> fetchFromUsername(descriptor.username()
                        .orElseThrow(() -> new IllegalArgumentException("Username missing for skin descriptor")));
                case EXTERNAL_URL -> fetchFromExternalUrl(descriptor.externalUrl()
                        .orElseThrow(() -> new IllegalArgumentException("URL missing for skin descriptor")));
                default -> throw new IllegalStateException("Unsupported skin source " + descriptor.source());
            };
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to resolve NPC skin (" + descriptor.source() + ")", exception);
        }
    }

    private NpcSkinPayload fetchFromExternalUrl(String url) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("url", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(MINESKIN_ENDPOINT))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Fulcrum-NpcToolkit/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("MineSkin responded with status " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || !data.has("texture")) {
            throw new IOException("MineSkin response missing texture data");
        }
        JsonObject texture = data.getAsJsonObject("texture");
        return toPayload(texture);
    }

    private NpcSkinPayload fetchFromUsername(String username) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_USERNAME_ENDPOINT + sanitize(username)))
                .timeout(DEFAULT_TIMEOUT)
                .header("User-Agent", "Fulcrum-NpcToolkit/1.0")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 204) {
            throw new IOException("Unknown Mojang username " + username);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Mojang username lookup failed (" + response.statusCode() + ")");
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String rawId = root.get("id").getAsString();
        return fetchFromUuid(UUID.fromString(formatUuid(rawId)));
    }

    private NpcSkinPayload fetchFromUuid(UUID uuid) throws IOException, InterruptedException {
        URI uri = URI.create(MOJANG_PROFILE_ENDPOINT + cleanUuid(uuid) + "?unsigned=false");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DEFAULT_TIMEOUT)
                .header("User-Agent", "Fulcrum-NpcToolkit/1.0")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Mojang profile lookup failed (" + response.statusCode() + ")");
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray properties = root.getAsJsonArray("properties");
        if (properties == null) {
            throw new IOException("Mojang profile response missing properties");
        }
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if ("textures".equalsIgnoreCase(property.get("name").getAsString())) {
                String value = property.get("value").getAsString();
                String signature = property.get("signature").getAsString();
                return new NpcSkinPayload(value, signature);
            }
        }
        throw new IOException("Mojang profile missing textures payload");
    }

    private String cacheKeyFor(NpcSkin skin) {
        String key = switch (skin.source()) {
            case MOJANG_UUID -> "uuid:" + skin.profileId().orElseThrow();
            case MOJANG_USERNAME -> "user:" + sanitize(skin.username().orElseThrow());
            case EXTERNAL_URL -> "url:" + sanitize(skin.externalUrl().orElseThrow());
            default -> throw new IllegalStateException("Unsupported cache key for source " + skin.source());
        };
        return sha256(key);
    }
}
