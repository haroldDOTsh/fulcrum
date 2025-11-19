package sh.harold.fulcrum.registry.social;

import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.friends.FriendSnapshot;
import sh.harold.fulcrum.api.friends.FriendSnapshot.BlockDetails;
import sh.harold.fulcrum.api.friends.FriendSnapshot.FriendDetails;

import java.util.*;

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
        Object friends = socialMap.get("friends");
        Object ignoresOut = socialMap.get("ignoresOut");
        Object ignoresIn = socialMap.get("ignoresIn");
        return FriendSnapshot.create(version, friends, ignoresOut, ignoresIn);
    }

    void save(UUID playerId, FriendSnapshot snapshot) {
        Document document = document(playerId);
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("version", snapshot.version());
        social.put("friends", serializeFriends(snapshot.friends().values()));
        social.put("ignoresOut", serializeBlocks(snapshot.ignoresOut().values()));
        social.put("ignoresIn", serializeBlocks(snapshot.ignoresIn().values()));
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

    private List<Map<String, Object>> serializeFriends(Collection<FriendDetails> friends) {
        if (friends == null || friends.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>(friends.size());
        for (FriendDetails details : friends) {
            if (details == null || details.playerId() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("playerId", details.playerId().toString());
            if (details.since() != null) {
                entry.put("since", details.since().toEpochMilli());
            }
            if (details.nickname() != null && !details.nickname().isBlank()) {
                entry.put("nickname", details.nickname());
            }
            entries.add(entry);
        }
        return entries;
    }

    private List<Map<String, Object>> serializeBlocks(Collection<BlockDetails> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>(blocks.size());
        for (BlockDetails block : blocks) {
            if (block == null || block.playerId() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("playerId", block.playerId().toString());
            if (block.blockedAt() != null) {
                entry.put("blockedAt", block.blockedAt().toEpochMilli());
            }
            entries.add(entry);
        }
        return entries;
    }
}
