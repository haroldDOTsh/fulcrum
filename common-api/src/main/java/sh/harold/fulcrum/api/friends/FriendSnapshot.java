package sh.harold.fulcrum.api.friends;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable aggregate of a player's friend state used by proxies and runtimes for hot-path checks.
 */
public record FriendSnapshot(
        long version,
        Set<UUID> friends,
        Set<UUID> ignoresOut,
        Set<UUID> ignoresIn
) {

    public FriendSnapshot {
        version = Math.max(0L, version);
        friends = sanitizeSet(friends);
        ignoresOut = sanitizeSet(ignoresOut);
        ignoresIn = sanitizeSet(ignoresIn);
    }

    private static Set<UUID> sanitizeSet(Set<UUID> source) {
        return source == null || source.isEmpty() ? Set.of() : Set.copyOf(source);
    }

    public static FriendSnapshot empty() {
        return new FriendSnapshot(0L, Set.of(), Set.of(), Set.of());
    }

    @JsonCreator
    public static FriendSnapshot create(@JsonProperty("version") long version,
                                        @JsonProperty("friends") Set<UUID> friends,
                                        @JsonProperty("ignoresOut") Object ignoresOut,
                                        @JsonProperty("ignoresIn") Object ignoresIn) {
        return new FriendSnapshot(
                version,
                friends,
                normalizeIgnores(ignoresOut),
                normalizeIgnores(ignoresIn));
    }

    private static Set<UUID> normalizeIgnores(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof Collection<?> collection) {
            LinkedHashSet<UUID> values = collection.stream()
                    .map(Objects::toString)
                    .map(UUID::fromString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return values.isEmpty() ? Set.of() : Set.copyOf(values);
        }
        if (raw instanceof Map<?, ?> map) {
            LinkedHashSet<UUID> flattened = new LinkedHashSet<>();
            map.values().forEach(value -> flattened.addAll(normalizeIgnores(value)));
            return flattened.isEmpty() ? Set.of() : Set.copyOf(flattened);
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Set.of(UUID.fromString(text));
        }
        if (raw instanceof UUID uuid) {
            return Set.of(uuid);
        }
        return Set.of();
    }

    public boolean isBlocking(UUID target) {
        return target != null && ignoresOut.contains(target);
    }

    public boolean isBlockedBy(UUID origin) {
        return origin != null && ignoresIn.contains(origin);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return friends.isEmpty()
                && ignoresOut.isEmpty()
                && ignoresIn.isEmpty();
    }
}
