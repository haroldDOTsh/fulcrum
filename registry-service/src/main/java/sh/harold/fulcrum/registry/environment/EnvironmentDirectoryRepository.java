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

        Document target = environments.document(document.id());
        if (!target.exists()) {
            environments.create(document.id(), payload);
        } else {
            target.set("tag", document.tag());
            target.set("modules", document.modules());
            target.set("description", document.description());
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
        return Optional.of(new EnvironmentDirectoryDocument(id, tag, modules, description));
    }
}
