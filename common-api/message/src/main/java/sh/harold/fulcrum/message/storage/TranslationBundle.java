package sh.harold.fulcrum.message.storage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationBundle {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final int schema;
    private final Locale locale;
    private final Map<String, String> translations;
    private final Map<String, String> tags;
    private final Map<String, Component> tagCache = new ConcurrentHashMap<>();

    private TranslationBundle(int schema,
                              Locale locale,
                              Map<String, String> translations,
                              Map<String, String> tags) {
        this.schema = schema;
        this.locale = locale;
        this.translations = translations;
        this.tags = tags;
    }

    public static TranslationBundle empty(Locale locale, int schema) {
        return new TranslationBundle(schema, locale, new HashMap<>(), new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public static TranslationBundle fromMap(Locale locale, Map<String, Object> data, int defaultSchema) {
        int schema = defaultSchema;
        Map<String, Object> metadata = (Map<String, Object>) data.get("metadata");
        if (metadata != null) {
            Object schemaValue = metadata.get("schema");
            if (schemaValue instanceof Number number) {
                schema = number.intValue();
            }
            Object localeValue = metadata.get("locale");
            if (localeValue instanceof String localeTag && !localeTag.isBlank()) {
                locale = Locale.forLanguageTag(localeTag);
            }
        }

        Map<String, String> translations = new HashMap<>();
        Object translationsObj = data.get("translations");
        if (translationsObj instanceof Map<?, ?> map) {
            flatten(map, "", translations);
        }

        Map<String, String> tags = new HashMap<>();
        Object tagsObj = data.get("tags");
        if (tagsObj instanceof Map<?, ?> tagMap) {
            flatten(tagMap, "", tags);
        }

        return new TranslationBundle(schema, locale, translations, tags);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<?, ?> source, String prefix, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String key)) {
                continue;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(nested, path, target);
            } else if (value != null) {
                target.put(path, value.toString());
            }
        }
    }

    public TranslationBundle withTranslation(String path, String value) {
        Map<String, String> newTranslations = new HashMap<>(translations);
        newTranslations.put(path, value);
        return new TranslationBundle(schema, locale, newTranslations, tags);
    }

    public TranslationBundle withTags(Map<String, String> newTags) {
        return new TranslationBundle(schema, locale, translations, new HashMap<>(newTags));
    }

    public int schema() {
        return schema;
    }

    public Locale locale() {
        return locale;
    }

    public Optional<String> translation(String path) {
        return Optional.ofNullable(translations.get(path));
    }

    public Map<String, String> translations() {
        return Collections.unmodifiableMap(translations);
    }

    public Optional<String> tag(String id) {
        return Optional.ofNullable(tags.get(id));
    }

    public Map<String, String> tags() {
        return Collections.unmodifiableMap(tags);
    }

    public Component tagComponent(String id) {
        Component existing = tagCache.get(id);
        if (existing != null) {
            return existing;
        }
        String value = tags.get(id);
        if (value == null) {
            return null;
        }
        try {
            Component component = MINI_MESSAGE.deserialize(value);
            tagCache.put(id, component);
            return component;
        } catch (Exception ex) {
            return null;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("schema", schema);
        metadata.put("locale", locale.toLanguageTag());
        map.put("metadata", metadata);
        map.put("translations", new HashMap<>(translations));
        map.put("tags", new HashMap<>(tags));
        return map;
    }
}
