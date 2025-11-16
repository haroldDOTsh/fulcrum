package sh.harold.fulcrum.registry.social;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.friends.FriendSnapshot;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists player social state inside the Mongo-backed players collection.
 */
public final class FriendSnapshotStore {

    private final DataAPI dataAPI;

    public FriendSnapshotStore(DataAPI dataAPI) {
        this.dataAPI = Objects.requireNonNull(dataAPI, "dataAPI");
    }

    FriendSnapshot load(UUID playerId) {
        Document document = document(playerId);
        if (!document.exists()) {
            return FriendSnapshot.empty();
        }
        Object rawSocial = document.get("social", null);
        if (!(rawSocial instanceof Map<?, ?> socialMap)) {
            return FriendSnapshot.empty();
        }
        long version = extractLong(socialMap.get("version"));
        Set<UUID> friends = extractUuidSet(socialMap.get("friends"));
        Set<UUID> ignoresOut = extractIgnores(socialMap.get("ignoresOut"));
        Set<UUID> ignoresIn = extractIgnores(socialMap.get("ignoresIn"));
        return new FriendSnapshot(version, friends, ignoresOut, ignoresIn);
    }

    void save(UUID playerId, FriendSnapshot snapshot) {
        Document document = document(playerId);
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("version", snapshot.version());
        social.put("friends", snapshot.friends().stream().map(UUID::toString).toList());
        social.put("ignoresOut", serializeIgnoreSet(snapshot.ignoresOut()));
        social.put("ignoresIn", serializeIgnoreSet(snapshot.ignoresIn()));
        document.set("social", social);
    }

    void shutdown() {
        dataAPI.shutdown();
    }

    private Document document(UUID playerId) {
        return dataAPI.collection("players").document(playerId.toString());
    }

    private long extractLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Set<UUID> extractUuidSet(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        return collection.stream()
                .map(Objects::toString)
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<UUID> extractIgnores(Object value) {
        if (value instanceof Collection<?> collection) {
            return extractUuidSet(collection);
        }
        if (value instanceof Map<?, ?> legacyMap) {
            Set<UUID> flattened = new HashSet<>();
            legacyMap.values().forEach(entry -> flattened.addAll(extractUuidSet(entry)));
            return Collections.unmodifiableSet(flattened);
        }
        return Set.of();
    }

    private List<String> serializeIgnoreSet(Set<UUID> set) {
        return set.stream().map(UUID::toString).toList();
    }
}
