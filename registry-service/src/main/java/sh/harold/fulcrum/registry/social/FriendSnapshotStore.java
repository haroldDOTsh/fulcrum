package sh.harold.fulcrum.registry.social;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.friends.FriendBlockScope;
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
        Map<FriendBlockScope, Set<UUID>> ignoresOut = extractScopeMap(socialMap.get("ignoresOut"));
        Map<FriendBlockScope, Set<UUID>> ignoresIn = extractScopeMap(socialMap.get("ignoresIn"));
        return new FriendSnapshot(version, friends, ignoresOut, ignoresIn);
    }

    void save(UUID playerId, FriendSnapshot snapshot) {
        Document document = document(playerId);
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("version", snapshot.version());
        social.put("friends", snapshot.friends().stream().map(UUID::toString).toList());
        social.put("ignoresOut", serializeScopeMap(snapshot.ignoresOut()));
        social.put("ignoresIn", serializeScopeMap(snapshot.ignoresIn()));
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

    private Map<FriendBlockScope, Set<UUID>> extractScopeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return emptyScopeMap();
        }
        EnumMap<FriendBlockScope, Set<UUID>> result = new EnumMap<>(FriendBlockScope.class);
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            Object entry = map.get(scope.name());
            result.put(scope, extractUuidSet(entry));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> serializeScopeMap(Map<FriendBlockScope, Set<UUID>> map) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            Set<UUID> entries = map.getOrDefault(scope, Set.of());
            serialized.put(scope.name(), entries.stream().map(UUID::toString).toList());
        }
        return serialized;
    }

    private Map<FriendBlockScope, Set<UUID>> emptyScopeMap() {
        EnumMap<FriendBlockScope, Set<UUID>> empty = new EnumMap<>(FriendBlockScope.class);
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            empty.put(scope, Set.of());
        }
        return empty;
    }
}
