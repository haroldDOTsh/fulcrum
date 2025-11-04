package sh.harold.fulcrum.registry.environment;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;

import java.util.*;

public final class EnvironmentDirectoryRepository implements AutoCloseable {
    private static final String COLLECTION_NAME = "network_environments";

    private final MongoConnectionAdapter connectionAdapter;
    private final DataAPI dataAPI;
    private final Collection environments;

    public EnvironmentDirectoryRepository(MongoConnectionAdapter connectionAdapter) {
        this.connectionAdapter = connectionAdapter;
        this.dataAPI = DataAPI.create(connectionAdapter);
        this.environments = dataAPI.collection(COLLECTION_NAME);
    }

    List<EnvironmentDirectoryDocument> loadAll() {
        List<Document> documents = environments.all();
        List<EnvironmentDirectoryDocument> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            if (document.exists()) {
                mapDocument(document).ifPresent(result::add);
            }
        }
        result.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return List.copyOf(result);
    }

    Optional<EnvironmentDirectoryDocument> load(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Document document = environments.document(id);
        if (!document.exists()) {
            return Optional.empty();
        }
        return mapDocument(document);
    }

    void save(EnvironmentDirectoryDocument document) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tag", document.tag());
        payload.put("modules", document.modules());
        payload.put("description", document.description());
        payload.put("minPlayers", document.minPlayers());
        payload.put("maxPlayers", document.maxPlayers());
        payload.put("playerFactor", document.playerFactor());
        payload.put("settings", new LinkedHashMap<>(document.settings()));

        Document target = environments.document(document.id());
        if (!target.exists()) {
            environments.create(document.id(), payload);
        } else {
            target.set("tag", document.tag());
            target.set("modules", document.modules());
            target.set("description", document.description());
            target.set("minPlayers", document.minPlayers());
            target.set("maxPlayers", document.maxPlayers());
            target.set("playerFactor", document.playerFactor());
            target.set("settings", new LinkedHashMap<>(document.settings()));
        }
    }

    @Override
    public void close() {
        // Mongo adapter lifecycle managed by registry service
    }

    private Optional<EnvironmentDirectoryDocument> mapDocument(Document document) {
        Map<String, Object> raw = document.toMap();
        String id = raw.get("_id") != null ? raw.get("_id").toString() : document.getId();
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String tag = raw.get("tag") != null ? raw.get("tag").toString() : id;
        List<String> modules = new ArrayList<>();
        Object modulesObj = raw.get("modules");
        if (modulesObj instanceof List<?> list) {
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) {
                    modules.add(value.toString());
                }
            }
        }
        String description = raw.get("description") != null ? raw.get("description").toString() : "";
        int minPlayers = readInt(raw.get("minPlayers"), 0);
        int maxPlayers = readInt(raw.get("maxPlayers"), minPlayers);
        if (maxPlayers < minPlayers) {
            maxPlayers = minPlayers;
        }
        double playerFactor = readDouble(raw.get("playerFactor"), 1.0D);
        Map<String, Object> settings = readSettings(raw.get("settings"));
        return Optional.of(new EnvironmentDirectoryDocument(
                id,
                tag,
                modules,
                description,
                minPlayers,
                maxPlayers,
                playerFactor,
                settings
        ));
    }

    private int readInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private double readDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSettings(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null) {
                    copy.put(k.toString(), v);
                }
            });
            return copy;
        }
        return Map.of();
    }
}
