package sh.harold.fulcrum.message.storage.mongo;

import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.message.storage.TranslationBundle;
import sh.harold.fulcrum.message.storage.TranslationRepository;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MongoTranslationRepository implements TranslationRepository {
    private final DataAPI dataAPI;
    private final String collectionName;
    private final int schemaVersion;

    public MongoTranslationRepository(DataAPI dataAPI, String collectionName, int schemaVersion) {
        this.dataAPI = dataAPI;
        this.collectionName = collectionName;
        this.schemaVersion = schemaVersion;
    }

    private static String documentId(String feature, Locale locale) {
        return feature + ":" + locale.toLanguageTag();
    }

    @Override
    public TranslationBundle load(String feature, Locale locale) {
        Locale targetLocale = locale == null ? Locale.US : locale;
        Collection collection = dataAPI.collection(collectionName);
        String documentId = documentId(feature, targetLocale);
        Document document = collection.select(documentId);
        if (document == null || !document.exists()) {
            Map<String, Object> seed = new HashMap<>();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("schema", schemaVersion);
            metadata.put("locale", targetLocale.toLanguageTag());
            seed.put("metadata", metadata);
            seed.put("translations", new HashMap<>());
            seed.put("tags", new HashMap<>());
            document = collection.create(documentId, seed);
        } else {
            document.set("metadata.schema", schemaVersion);
            document.set("metadata.locale", targetLocale.toLanguageTag());
        }
        return TranslationBundle.fromMap(targetLocale, document.toMap(), schemaVersion);
    }

    @Override
    public void saveTranslation(String feature, Locale locale, String path, String value) {
        Locale targetLocale = locale == null ? Locale.US : locale;
        Document document = ensureDocument(feature, targetLocale);
        document.set("translations." + path, value);
        document.set("metadata.schema", schemaVersion);
        document.set("metadata.locale", targetLocale.toLanguageTag());
    }

    @Override
    public void saveTags(String feature, Locale locale, Map<String, String> tags) {
        Locale targetLocale = locale == null ? Locale.US : locale;
        Document document = ensureDocument(feature, targetLocale);
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            document.set("tags." + entry.getKey(), entry.getValue());
        }
        document.set("metadata.schema", schemaVersion);
        document.set("metadata.locale", targetLocale.toLanguageTag());
    }

    @Override
    public Map<String, TranslationBundle> loadAll() {
        Collection collection = dataAPI.collection(collectionName);
        Map<String, TranslationBundle> bundles = new HashMap<>();
        try {
            for (Document document : collection.all()) {
                if (document == null) {
                    continue;
                }
                String id = document.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                String[] parts = id.split(":", 2);
                if (parts.length != 2) {
                    continue;
                }
                Locale locale = Locale.forLanguageTag(parts[1]);
                TranslationBundle bundle = TranslationBundle.fromMap(locale, document.toMap(), schemaVersion);
                bundles.put(id, bundle);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load translation bundles", ex);
        }
        return bundles;
    }

    private Document ensureDocument(String feature, Locale locale) {
        Collection collection = dataAPI.collection(collectionName);
        String documentId = documentId(feature, locale);
        Document document = collection.select(documentId);
        if (document != null && document.exists()) {
            return document;
        }
        Map<String, Object> seed = new HashMap<>();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("schema", schemaVersion);
        metadata.put("locale", locale.toLanguageTag());
        seed.put("metadata", metadata);
        seed.put("translations", new HashMap<>());
        seed.put("tags", new HashMap<>());
        return collection.create(documentId, seed);
    }
}
