package sh.harold.fulcrum.message.storage;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationCache {
    private final TranslationRepository repository;
    private final Locale defaultLocale;
    private final Map<String, TranslationBundle> cache = new ConcurrentHashMap<>();

    public TranslationCache(TranslationRepository repository, Locale defaultLocale) {
        this.repository = repository;
        this.defaultLocale = defaultLocale == null ? Locale.US : defaultLocale;
    }

    private static String placeholder(String identifier, int argCount) {
        StringBuilder builder = new StringBuilder(identifier);
        for (int i = 0; i < argCount; i++) {
            builder.append(" {arg").append(i).append('}');
        }
        return builder.toString();
    }

    private static String key(String feature, Locale locale) {
        return feature + ":" + locale.toLanguageTag();
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    public TranslationBundle bundle(String feature, Locale locale) {
        Locale targetLocale = locale == null ? defaultLocale : locale;
        String key = key(feature, targetLocale);
        return cache.computeIfAbsent(key, ignored -> repository.load(feature, targetLocale));
    }

    public String resolveTranslation(String feature,
                                     Locale locale,
                                     String path,
                                     String rawIdentifier,
                                     int argCount) {
        TranslationBundle bundle = bundle(feature, locale);
        Optional<String> existing = bundle.translation(path);
        if (existing.isPresent()) {
            return existing.get();
        }
        String placeholder = placeholder(rawIdentifier, argCount);
        repository.saveTranslation(feature, locale == null ? defaultLocale : locale, path, placeholder);
        updateBundle(feature, locale, bundle.withTranslation(path, placeholder));
        return placeholder;
    }

    public Optional<String> resolveTag(String feature, Locale locale, String tagId) {
        return bundle(feature, locale).tag(tagId);
    }

    public TranslationBundle refresh(String feature, Locale locale) {
        Locale targetLocale = locale == null ? defaultLocale : locale;
        TranslationBundle bundle = repository.load(feature, targetLocale);
        cache.put(key(feature, targetLocale), bundle);
        return bundle;
    }

    public void clear() {
        cache.clear();
    }

    public void preload(Map<String, TranslationBundle> bundles) {
        if (bundles == null) {
            return;
        }
        cache.putAll(bundles);
    }

    private void updateBundle(String feature, Locale locale, TranslationBundle bundle) {
        Locale targetLocale = locale == null ? defaultLocale : locale;
        cache.put(key(feature, targetLocale), bundle);
    }
}
