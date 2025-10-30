package sh.harold.fulcrum.message.storage;

import java.util.Locale;
import java.util.Map;

public interface TranslationRepository {

    TranslationBundle load(String feature, Locale locale);

    void saveTranslation(String feature, Locale locale, String path, String value);

    void saveTags(String feature, Locale locale, Map<String, String> tags);

    Map<String, TranslationBundle> loadAll();
}
