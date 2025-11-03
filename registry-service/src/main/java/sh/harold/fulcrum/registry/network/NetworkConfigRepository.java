package sh.harold.fulcrum.registry.network;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;

import java.time.Instant;
import java.util.*;

public final class NetworkConfigRepository implements AutoCloseable {
    private static final String ACTIVE_DOCUMENT_ID = "active";

    private final MongoConnectionAdapter connectionAdapter;
    private final DataAPI dataAPI;
    private final Collection profiles;
    private final Collection activeMarkers;

    public NetworkConfigRepository(MongoConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
        this.dataAPI = DataAPI.create(connectionAdapter);
        this.profiles = dataAPI.collection("network_settings");
        this.activeMarkers = dataAPI.collection("network_settings_active");
    }

    List<NetworkProfileDocument> loadProfiles() {
        List<Document> documents = profiles.all();
        List<NetworkProfileDocument> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document.exists()) {
                mapDocument(document).ifPresent(result::add);
            }
        }
        result.sort((a, b) -> a.profileId().compareToIgnoreCase(b.profileId()));
        return Collections.unmodifiableList(result);
    }

    Optional<NetworkProfileDocument> loadProfile(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        Document document = profiles.document(profileId);
        if (!document.exists()) {
            return Optional.empty();
        }
        return mapDocument(document);
    }

    Optional<ActiveProfileMarker> loadActiveMarker() {
        Document marker = activeMarkers.document(ACTIVE_DOCUMENT_ID);
        if (!marker.exists()) {
            return Optional.empty();
        }
        Map<String, Object> raw = marker.toMap();
        String profileId = raw.get("profileId") != null ? raw.get("profileId").toString() : null;
        String tag = raw.get("tag") != null ? raw.get("tag").toString() : "";
        Instant updatedAt = parseInstant(raw.get("updatedAt"));
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ActiveProfileMarker(profileId, tag, updatedAt));
    }

    void updateActiveMarker(String profileId, String tag, Instant updatedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profileId", profileId);
        data.put("tag", tag);
        data.put("updatedAt", updatedAt.toString());

        Document marker = activeMarkers.document(ACTIVE_DOCUMENT_ID);
        if (!marker.exists()) {
            activeMarkers.create(ACTIVE_DOCUMENT_ID, data);
            return;
        }

        marker.set("profileId", profileId);
        marker.set("tag", tag);
        marker.set("updatedAt", updatedAt.toString());
    }

    void updateProfileTimestamp(String profileId, Instant updatedAt) {
        Document document = profiles.document(profileId);
        if (document.exists()) {
            document.set("updatedAt", updatedAt.toString());
        }
    }

    void saveProfile(NetworkProfileDocument profile) {
        Map<String, Object> scoreboard = new LinkedHashMap<>();
        scoreboard.put("title", profile.scoreboardTitle());
        scoreboard.put("footer", profile.scoreboardFooter());

        Map<String, Object> ranks = new LinkedHashMap<>();
        profile.ranks().forEach((rankId, visual) -> {
            Map<String, Object> visualMap = new LinkedHashMap<>();
            visualMap.put("displayName", visual.displayName());
            visualMap.put("colorCode", visual.colorCode());
            visualMap.put("fullPrefix", visual.fullPrefix());
            visualMap.put("shortPrefix", visual.shortPrefix());
            visualMap.put("nameColor", visual.nameColor());
            ranks.put(rankId, visualMap);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tag", profile.tag());
        data.put("serverIp", profile.serverIp());
        data.put("motd", profile.motd());
        data.put("scoreboard", scoreboard);
        data.put("ranks", ranks);
        data.put("updatedAt", profile.updatedAt().toString());

        Document document = profiles.document(profile.profileId());
        if (!document.exists()) {
            profiles.create(profile.profileId(), data);
        } else {
            document.set("tag", profile.tag());
            document.set("serverIp", profile.serverIp());
            document.set("motd", profile.motd());
            document.set("scoreboard", scoreboard);
            document.set("ranks", ranks);
            document.set("updatedAt", profile.updatedAt().toString());
        }
    }

    @Override
    public void close() {
        dataAPI.shutdown();
        // Mongo adapter lifecycle managed by registry service
    }

    private Optional<NetworkProfileDocument> mapDocument(Document document) {
        Map<String, Object> raw = document.toMap();
        Map<String, Object> normalized = normalizeDocumentMap(raw);
        String id = normalized.containsKey("_id") ? Objects.toString(normalized.get("_id")) : document.getId();
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        String tag = Objects.toString(normalized.getOrDefault("tag", id));
        String serverIp = Objects.toString(normalized.getOrDefault("serverIp", ""));

        List<String> motd = new ArrayList<>();
        Object motdObj = normalized.get("motd");
        if (motdObj instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    motd.add(value.toString());
                }
            }
        }

        Map<String, Object> scoreboard = getChildMap(normalized, "scoreboard");
        String title = Objects.toString(scoreboard.getOrDefault("title", ""));
        String footer = Objects.toString(scoreboard.getOrDefault("footer", ""));

        Map<String, NetworkProfileDocument.RankVisualDocument> ranks = new LinkedHashMap<>();
        Map<String, Object> ranksRaw = getChildMap(normalized, "ranks");
        for (Map.Entry<String, Object> entry : ranksRaw.entrySet()) {
            String rankId = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> visualMap)) {
                continue;
            }
            Map<String, Object> visual = new LinkedHashMap<>();
            visualMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    visual.put(k.toString(), v);
                }
            });
            NetworkProfileDocument.RankVisualDocument rankVisual =
                    new NetworkProfileDocument.RankVisualDocument(
                            Objects.toString(visual.getOrDefault("displayName", "")),
                            Objects.toString(visual.getOrDefault("colorCode", "")),
                            Objects.toString(visual.getOrDefault("fullPrefix", "")),
                            Objects.toString(visual.getOrDefault("shortPrefix", "")),
                            Objects.toString(visual.getOrDefault("nameColor", ""))
                    );
            ranks.put(rankId, rankVisual);
        }

        Instant updatedAt = parseInstant(normalized.get("updatedAt"));

        NetworkProfileDocument documentModel = new NetworkProfileDocument(
                id,
                tag,
                serverIp,
                motd,
                title,
                footer,
                ranks,
                updatedAt,
                normalized
        );

        return Optional.of(documentModel);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getChildMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (child instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(k.toString(), v);
                }
            });
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception ex) {
            return Instant.EPOCH;
        }
    }

    record ActiveProfileMarker(String profileId, String tag, Instant updatedAt) {
    }

    private Map<String, Object> normalizeDocumentMap(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            normalized.put(key, normalizeValue(value));
        });
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> child = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    child.put(k.toString(), normalizeValue(v));
                }
            });
            return child;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(normalizeValue(element));
            }
            return copy;
        }
        return value;
    }
}
