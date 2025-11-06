package sh.harold.fulcrum.registry.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.message.storage.TranslationBundle;
import sh.harold.fulcrum.message.storage.TranslationRepository;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Seeds shared message tags (e.g., DAEMON) into the translation repository when missing.
 */
public final class MessageTagSeeder {
    private static final String RESOURCE = "message-tags-defaults.json";

    private final TranslationRepository repository;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    public MessageTagSeeder(TranslationRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    public void seedIfMissing() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                logger.warn("Message tag defaults resource '{}' missing; skipping seed.", RESOURCE);
                return;
            }

            SeedDocument document = mapper.readValue(input, SeedDocument.class);
            if (document.feature() == null || document.feature().isBlank()) {
                logger.warn("Message tag defaults missing feature identifier; skipping seed.");
                return;
            }

            Locale locale = document.locale() == null || document.locale().isBlank()
                    ? Locale.US
                    : Locale.forLanguageTag(document.locale());
            TranslationBundle bundle = repository.load(document.feature(), locale);
            Map<String, String> missing = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : document.tags().entrySet()) {
                if (bundle.tag(entry.getKey()).isEmpty()) {
                    missing.put(entry.getKey(), entry.getValue());
                }
            }

            if (missing.isEmpty()) {
                logger.debug("All message tags already present for feature '{}' ({})", document.feature(), locale);
                return;
            }

            repository.saveTags(document.feature(), locale, missing);
            logger.info("Seeded {} message tag(s) into feature '{}' ({})", missing.size(), document.feature(), locale);
        } catch (Exception ex) {
            logger.error("Failed to seed message tag defaults", ex);
        }
    }

    private record SeedDocument(String feature, String locale, Map<String, String> tags) {
        private SeedDocument {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }
}
