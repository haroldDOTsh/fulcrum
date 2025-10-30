package sh.harold.fulcrum.velocity.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.MessageFacade;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.impl.StandardMessageFacade;
import sh.harold.fulcrum.message.locale.AudienceLocaleResolver;
import sh.harold.fulcrum.message.storage.TranslationBundle;
import sh.harold.fulcrum.message.storage.TranslationCache;
import sh.harold.fulcrum.message.storage.TranslationRepository;
import sh.harold.fulcrum.message.storage.mongo.MongoTranslationRepository;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class VelocityMessageFeature implements VelocityFeature {
    private static final int SCHEMA_VERSION = 1;

    private TranslationCache translationCache;
    private Logger logger;

    private static String sanitizeId(String id) {
        int idx = id.indexOf(':');
        if (idx < 0) {
            return id;
        }
        String feature = id.substring(0, idx);
        String locale = id.substring(idx + 1);
        return feature + "__" + locale;
    }

    @Override
    public String getName() {
        return "Message";
    }

    @Override
    public int getPriority() {
        return 25; // After DataAPI (20) but before features relying on messaging
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;

        DataAPI dataAPI = serviceLocator.getService(DataAPI.class).orElse(null);
        if (dataAPI == null) {
            logger.warn("[Message] DataAPI unavailable; message facade disabled on proxy.");
            return;
        }

        Path dataDirectory = serviceLocator.getService(Path.class).orElse(null);
        if (dataDirectory == null) {
            logger.warn("[Message] Data directory unavailable; message cache snapshots disabled.");
        }

        TranslationRepository repository = new MongoTranslationRepository(dataAPI, "message_translations", SCHEMA_VERSION);
        Locale defaultLocale = Locale.US;
        translationCache = new TranslationCache(repository, defaultLocale);

        Path cacheDir = dataDirectory != null
                ? dataDirectory.resolve("lang").resolve("cache")
                : null;

        Map<String, TranslationBundle> bundles;
        try {
            bundles = repository.loadAll();
            translationCache.preload(bundles);
            writeSnapshots(cacheDir, bundles);
            logger.info("[Message] Loaded {} translation bundle(s) from Mongo.", bundles.size());
        } catch (Exception ex) {
            logger.warn("[Message] Failed to load translations from Mongo: {}", ex.getMessage());
            bundles = loadSnapshots(cacheDir);
            translationCache.preload(bundles);
            logger.info("[Message] Loaded {} translation bundle(s) from local cache.", bundles.size());
        }

        AudienceLocaleResolver localeResolver = audience -> {
            if (audience instanceof Player player) {
                Locale locale = player.getEffectiveLocale();
                return locale != null ? locale : defaultLocale;
            }
            return defaultLocale;
        };

        VelocityPlayerSessionService sessionService = serviceLocator.getService(VelocityPlayerSessionService.class).orElse(null);
        DebugGate debugGate = new VelocityMessageDebugGate(sessionService, dataAPI, logger);

        MessageFacade facade = new StandardMessageFacade(translationCache, localeResolver, debugGate);
        Message.setFacade(facade);

        serviceLocator.register(TranslationCache.class, translationCache);
    }

    @Override
    public void shutdown() {
        if (translationCache != null) {
            translationCache.clear();
        }
    }

    private void writeSnapshots(Path directory, Map<String, TranslationBundle> bundles) {
        if (directory == null || bundles == null || bundles.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            logger.warn("[Message] Failed to create snapshot directory: {}", e.getMessage());
            return;
        }

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        bundles.forEach((id, bundle) -> {
            String filename = sanitizeId(id) + ".json";
            Path target = directory.resolve(filename);
            try {
                mapper.writeValue(target.toFile(), bundle.toMap());
            } catch (Exception ex) {
                logger.warn("[Message] Failed to write snapshot for {}: {}", id, ex.getMessage());
            }
        });
    }

    private Map<String, TranslationBundle> loadSnapshots(Path directory) {
        Map<String, TranslationBundle> bundles = new HashMap<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return bundles;
        }

        ObjectMapper mapper = new ObjectMapper();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        String base = path.getFileName().toString().replaceFirst("\\.json$", "");
                        int idx = base.lastIndexOf("__");
                        if (idx <= 0) {
                            return;
                        }
                        String feature = base.substring(0, idx);
                        String localeTag = base.substring(idx + 2);
                        try {
                            Map<String, Object> raw = mapper.readValue(path.toFile(), new TypeReference<Map<String, Object>>() {
                            });
                            Locale locale = Locale.forLanguageTag(localeTag);
                            TranslationBundle bundle = TranslationBundle.fromMap(locale, raw, SCHEMA_VERSION);
                            bundles.put(feature + ":" + localeTag, bundle);
                        } catch (Exception ex) {
                            logger.warn("[Message] Failed to load snapshot {}: {}", path.getFileName(), ex.getMessage());
                        }
                    });
        } catch (IOException ex) {
            logger.warn("[Message] Failed to read translation snapshots: {}", ex.getMessage());
        }
        return bundles;
    }
}
