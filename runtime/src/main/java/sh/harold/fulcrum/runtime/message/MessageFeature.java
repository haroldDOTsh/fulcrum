package sh.harold.fulcrum.runtime.message;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.MessageFacade;
import sh.harold.fulcrum.message.debug.DebugGate;
import sh.harold.fulcrum.message.impl.StandardMessageFacade;
import sh.harold.fulcrum.message.locale.AudienceLocaleResolver;
import sh.harold.fulcrum.message.storage.TranslationBundle;
import sh.harold.fulcrum.message.storage.TranslationCache;
import sh.harold.fulcrum.message.storage.TranslationRepository;
import sh.harold.fulcrum.message.storage.mongo.MongoTranslationRepository;
import sh.harold.fulcrum.runtime.message.command.RefreshLangCacheCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MessageFeature implements PluginFeature {
    private static final int SCHEMA_VERSION = 1;

    private JavaPlugin owningPlugin;
    private TranslationCache translationCache;

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
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.owningPlugin = plugin;
        DataAPI dataAPI = container.get(DataAPI.class);
        RankService rankService = container.getOptional(RankService.class).orElse(null);

        TranslationRepository repository = new MongoTranslationRepository(dataAPI, "message_translations", SCHEMA_VERSION);
        Locale defaultLocale = Locale.US;
        translationCache = new TranslationCache(repository, defaultLocale);

        Path cacheDir = plugin.getDataFolder().toPath().resolve("lang").resolve("cache");
        Map<String, TranslationBundle> bundles;
        try {
            bundles = repository.loadAll();
            translationCache.preload(bundles);
            writeSnapshots(cacheDir, bundles);
            plugin.getLogger().info("[Message] Loaded " + bundles.size() + " translation bundle(s) from Mongo.");
        } catch (Exception ex) {
            plugin.getLogger().warning("[Message] Failed to load translations from Mongo: " + ex.getMessage());
            bundles = loadSnapshots(cacheDir);
            translationCache.preload(bundles);
            plugin.getLogger().info("[Message] Loaded " + bundles.size() + " translation bundle(s) from local cache.");
        }

        AudienceLocaleResolver localeResolver = new RuntimeLocaleResolver(defaultLocale);
        DebugGate debugGate = new RuntimeDebugGate(rankService);
        MessageFacade facade = new StandardMessageFacade(translationCache, localeResolver, debugGate);

        container.register(TranslationCache.class, translationCache);
        Message.setFacade(facade);

        CommandRegistrar.register(RefreshLangCacheCommand.create(translationCache));
    }

    @Override
    public void shutdown() {
        if (translationCache != null) {
            translationCache.clear();
        }
        owningPlugin = null;
    }

    @Override
    public int getPriority() {
        return 12; // After DataAPI (10) and before mid-tier features
    }

    private void writeSnapshots(Path directory, Map<String, TranslationBundle> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            log("Failed to create snapshot directory: " + e.getMessage());
            return;
        }
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        bundles.forEach((id, bundle) -> {
            String filename = sanitizeId(id) + ".json";
            Path target = directory.resolve(filename);
            try {
                mapper.writeValue(target.toFile(), bundle.toMap());
            } catch (Exception ignored) {
                log("Failed to write snapshot for " + id + ": " + ignored.getMessage());
            }
        });
    }

    private Map<String, TranslationBundle> loadSnapshots(Path directory) {
        Map<String, TranslationBundle> bundles = new HashMap<>();
        if (!Files.isDirectory(directory)) {
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
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException ex) {
            log("Failed to read translation snapshots: " + ex.getMessage());
        }
        return bundles;
    }

    private void log(String message) {
        if (owningPlugin != null) {
            owningPlugin.getLogger().warning("[Message] " + message);
        }
    }
}
