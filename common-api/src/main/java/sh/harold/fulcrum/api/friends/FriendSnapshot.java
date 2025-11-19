package sh.harold.fulcrum.api.friends;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Immutable aggregate of a player's friend state used by proxies and runtimes for hot-path checks.
 */
public record FriendSnapshot(
        long version,
        Map<UUID, FriendDetails> friends,
        Map<UUID, BlockDetails> ignoresOut,
        Map<UUID, BlockDetails> ignoresIn
) {

    public FriendSnapshot {
        version = Math.max(0L, version);
        friends = sanitizeFriendMap(friends);
        ignoresOut = sanitizeBlockMap(ignoresOut);
        ignoresIn = sanitizeBlockMap(ignoresIn);
    }

    private static Map<UUID, FriendDetails> sanitizeFriendMap(Map<UUID, FriendDetails> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, FriendDetails> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            UUID resolvedKey = key != null ? key : (value != null ? value.playerId() : null);
            if (resolvedKey == null) {
                return;
            }
            FriendDetails normalized = value == null
                    ? new FriendDetails(resolvedKey, null, null)
                    : value.withFallbackId(resolvedKey);
            sanitized.put(resolvedKey, normalized);
        });
        return Collections.unmodifiableMap(sanitized);
    }

    private static Map<UUID, BlockDetails> sanitizeBlockMap(Map<UUID, BlockDetails> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, BlockDetails> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            UUID resolvedKey = key != null ? key : (value != null ? value.playerId() : null);
            if (resolvedKey == null) {
                return;
            }
            BlockDetails normalized = value == null
                    ? new BlockDetails(resolvedKey, null)
                    : value.withFallbackId(resolvedKey);
            sanitized.put(resolvedKey, normalized);
        });
        return Collections.unmodifiableMap(sanitized);
    }

    public static FriendSnapshot empty() {
        return new FriendSnapshot(0L, Map.of(), Map.of(), Map.of());
    }

    @JsonCreator
    public static FriendSnapshot create(@JsonProperty("version") long version,
                                        @JsonProperty("friends") Object friends,
                                        @JsonProperty("ignoresOut") Object ignoresOut,
                                        @JsonProperty("ignoresIn") Object ignoresIn) {
        return new FriendSnapshot(
                version,
                normalizeFriends(friends),
                normalizeBlocks(ignoresOut),
                normalizeBlocks(ignoresIn));
    }

    private static Map<UUID, FriendDetails> normalizeFriends(Object raw) {
        if (!(raw instanceof Collection<?> collection)) {
            return Map.of();
        }
        LinkedHashMap<UUID, FriendDetails> entries = new LinkedHashMap<>();
        for (Object element : collection) {
            FriendDetails parsed = parseFriendDetails(element);
            if (parsed != null) {
                entries.put(parsed.playerId(), parsed);
            }
        }
        return entries.isEmpty() ? Map.of() : Collections.unmodifiableMap(entries);
    }

    private static FriendDetails parseFriendDetails(Object element) {
        if (element instanceof FriendDetails friendDetails) {
            return friendDetails;
        }
        if (element instanceof UUID uuid) {
            return new FriendDetails(uuid, null, null);
        }
        if (element instanceof String text && !text.isBlank()) {
            return new FriendDetails(UUID.fromString(text.trim()), null, null);
        }
        if (element instanceof Map<?, ?> map) {
            UUID playerId = parseUuid(map.get("playerId"));
            Instant since = parseInstant(map.get("since"));
            String nickname = optionalText(map.get("nickname"));
            return playerId == null ? null : new FriendDetails(playerId, since, nickname);
        }
        return null;
    }

    private static Map<UUID, BlockDetails> normalizeBlocks(Object raw) {
        if (!(raw instanceof Collection<?> collection)) {
            return Map.of();
        }
        LinkedHashMap<UUID, BlockDetails> entries = new LinkedHashMap<>();
        for (Object element : collection) {
            BlockDetails parsed = parseBlockDetails(element);
            if (parsed != null) {
                entries.put(parsed.playerId(), parsed);
            }
        }
        return entries.isEmpty() ? Map.of() : Collections.unmodifiableMap(entries);
    }

    private static BlockDetails parseBlockDetails(Object element) {
        if (element instanceof BlockDetails blockDetails) {
            return blockDetails;
        }
        if (element instanceof UUID uuid) {
            return new BlockDetails(uuid, null);
        }
        if (element instanceof String text && !text.isBlank()) {
            return new BlockDetails(UUID.fromString(text.trim()), null);
        }
        if (element instanceof Map<?, ?> map) {
            UUID playerId = parseUuid(map.get("playerId"));
            Instant blockedAt = parseInstant(map.get("blockedAt"));
            return playerId == null ? null : new BlockDetails(playerId, blockedAt);
        }
        return null;
    }

    private static UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String text && !text.isBlank()) {
            return UUID.fromString(text.trim());
        }
        return null;
    }

    private static Instant parseInstant(Object raw) {
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof Number number) {
            long epochMillis = number.longValue();
            return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
        }
        if (raw instanceof String text && !text.isBlank()) {
            String trimmed = text.trim();
            try {
                if (trimmed.chars().allMatch(Character::isDigit)) {
                    long epochMillis = Long.parseLong(trimmed);
                    return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
                }
                return Instant.parse(trimmed);
            } catch (DateTimeParseException | NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String optionalText(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString();
        return text.isBlank() ? null : text;
    }

    @JsonIgnore
    public Set<UUID> friendIds() {
        return friends.keySet();
    }

    @JsonIgnore
    public Set<UUID> ignoresOutIds() {
        return ignoresOut.keySet();
    }

    @JsonIgnore
    public Set<UUID> ignoresInIds() {
        return ignoresIn.keySet();
    }

    public Optional<FriendDetails> friendDetails(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(friends.get(playerId));
    }

    public Optional<BlockDetails> blockDetailsOut(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(ignoresOut.get(playerId));
    }

    public Optional<BlockDetails> blockDetailsIn(UUID playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(ignoresIn.get(playerId));
    }

    public boolean isBlocking(UUID target) {
        return target != null && ignoresOut.containsKey(target);
    }

    public boolean isBlockedBy(UUID origin) {
        return origin != null && ignoresIn.containsKey(origin);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return friends.isEmpty()
                && ignoresOut.isEmpty()
                && ignoresIn.isEmpty();
    }

    public record FriendDetails(UUID playerId, Instant since, String nickname) {
        private FriendDetails withFallbackId(UUID fallback) {
            UUID resolved = playerId != null ? playerId : Objects.requireNonNull(fallback, "fallback");
            return new FriendDetails(resolved, since, nickname);
        }
    }

    public record BlockDetails(UUID playerId, Instant blockedAt) {
        private BlockDetails withFallbackId(UUID fallback) {
            UUID resolved = playerId != null ? playerId : Objects.requireNonNull(fallback, "fallback");
            return new BlockDetails(resolved, blockedAt);
        }
    }
}
