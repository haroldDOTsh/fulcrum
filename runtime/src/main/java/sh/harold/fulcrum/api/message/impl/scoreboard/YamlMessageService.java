package sh.harold.fulcrum.api.message.impl.scoreboard;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.MessageService;
import sh.harold.fulcrum.api.message.MessageStyle;
import sh.harold.fulcrum.api.message.util.DefaultTagFormatter;
import sh.harold.fulcrum.api.message.util.GenericResponse;
import sh.harold.fulcrum.api.message.util.MessageTag;
import sh.harold.fulcrum.api.message.util.TagFormatter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class YamlMessageService implements MessageService {
    // ThreadLocal to allow Message API to pass argument count for placeholder generation
    private static final ThreadLocal<Integer> ARG_COUNT_CONTEXT = new ThreadLocal<>();
    private final Path langDirectory;
    // Structure: <Feature, <Locale, Config>>
    private final Map<String, Map<Locale, FileConfiguration>> translations = new HashMap<>();
    private final Locale defaultLocale = Locale.US;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private TagFormatter tagFormatter = new DefaultTagFormatter();

    public YamlMessageService(Path pluginDataFolder) {
        // Avoid double-nesting /lang/lang
        if (pluginDataFolder.getFileName() != null && pluginDataFolder.getFileName().toString().equalsIgnoreCase("lang")) {
            this.langDirectory = pluginDataFolder;
        } else {
            this.langDirectory = pluginDataFolder.resolve("lang");
        }
        try {
            if (Files.notExists(langDirectory)) {
                Files.createDirectories(langDirectory);
            }
            loadTranslations();
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to create or load language directory: " + e.getMessage());
        }
    }

    @Override
    public void setArgCountContext(int argCount) {
        ARG_COUNT_CONTEXT.set(argCount);
    }

    public void loadTranslations() {
        translations.clear();
        try (Stream<Path> featureDirs = Files.walk(langDirectory, 1)) {
            featureDirs
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(langDirectory))
                    .forEach(featureDir -> {
                        String featureName = featureDir.getFileName().toString();
                        Map<Locale, FileConfiguration> localeMap = new HashMap<>();
                        try (Stream<Path> langFiles = Files.walk(featureDir, 1)) {
                            langFiles
                                    .filter(path -> !Files.isDirectory(path) && path.toString().endsWith(".yml"))
                                    .forEach(langFile -> {
                                        String fileName = langFile.getFileName().toString().replace(".yml", "");
                                        Locale locale = Locale.forLanguageTag(fileName.replace("_", "-"));
                                        FileConfiguration config = YamlConfiguration.loadConfiguration(langFile.toFile());
                                        localeMap.put(locale, config);
                                    });
                        } catch (IOException e) {
                            Bukkit.getLogger().severe("Failed to load translation files for feature '" + featureName + "': " + e.getMessage());
                        }
                        translations.put(featureName, localeMap);
                    });
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to load translation feature: " + e.getMessage());
        }
    }

    @Override
    public String getTranslation(String key, Locale locale) {
        String[] parts = key.split("\\.", 2);
        if (parts.length < 2) {
            return key; // Not a valid nested key
        }

        String feature = parts[0];
        String path = parts[1];

        Map<Locale, FileConfiguration> featureTranslations = translations.get(feature);
        FileConfiguration config = null;
        if (featureTranslations != null) {
            config = featureTranslations.getOrDefault(locale, featureTranslations.get(defaultLocale));
            if (config != null && config.contains(path)) {
                return config.getString(path, key);
            }
        }

        // If missing, create the file and key dynamically
        try {
            Path featureDir = langDirectory.resolve(feature);
            if (Files.notExists(featureDir)) {
                Files.createDirectories(featureDir);
            }
            String localeName = locale.toLanguageTag().replace("-", "_");
            Path langFile = featureDir.resolve(localeName + ".yml");
            FileConfiguration newConfig;
            if (Files.exists(langFile)) {
                newConfig = YamlConfiguration.loadConfiguration(langFile.toFile());
            } else {
                newConfig = new YamlConfiguration();
            }
            if (!newConfig.contains(path)) {
                // Use ThreadLocal context if set, otherwise fallback to 1
                int argCount = 1;
                Integer ctx = ARG_COUNT_CONTEXT.get();
                if (ctx != null) {
                    argCount = ctx;
                    ARG_COUNT_CONTEXT.remove();
                }
                StringBuilder sb = new StringBuilder(key);
                for (int i = 0; i < argCount; i++) {
                    sb.append(" {arg").append(i + 1).append("}");
                }
                newConfig.set(path, sb.toString());
                newConfig.save(langFile.toFile());
            }
            // Reload into memory
            translations.computeIfAbsent(feature, f -> new HashMap<>()).put(locale, newConfig);
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to create missing translation for '" + key + "': " + e.getMessage());
        }
        return key;
    }

    // (No hacks: argument count is now passed via ThreadLocal from Message API)

    private Component deserialize(String message) {
        return miniMessage.deserialize(message);
    }

    @Override
    public void sendMessage(UUID playerUuid, Component message) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    @Override
    public void broadcastMessage(Component message) {
        Bukkit.broadcast(message);
    }

    @Override
    public void sendStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args) {
        sendMessage(playerId, getStyledMessage(playerId, style, translationKey, args));
    }

    @Override
    public void sendGenericResponse(UUID playerId, GenericResponse response) {
        sendStyledMessage(playerId, MessageStyle.ERROR, response.getKey());
    }

    @Override
    public void broadcastStyledMessage(MessageStyle style, String translationKey, Object... args) {
        broadcastMessage(getStyledMessage(defaultLocale, style, translationKey, args));
    }

    @Override
    public Component getStyledMessage(UUID playerId, MessageStyle style, String translationKey, Object... args) {
        return getStyledMessage(getPlayerLocale(playerId), style, translationKey, args);
    }


    @Override
    public void sendStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(getStyledMessageWithTags(playerId, style, translationKey, tags, args));
        }
    }

    @Override
    public void broadcastStyledMessageWithTags(MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        Bukkit.broadcast(getStyledMessageWithTags(defaultLocale, style, translationKey, tags, args));
    }

    @Override
    public Component getStyledMessageWithTags(UUID playerId, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        return getStyledMessageWithTags(getPlayerLocale(playerId), style, translationKey, tags, args);
    }

    @Override
    public Component getStyledMessageWithTags(Locale locale, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        String tagsString = tags.stream()
                .map(tagFormatter::formatTag)
                .collect(Collectors.joining(""));
        Component message = getStyledMessage(locale, style, translationKey, args);
        return deserialize(tagsString).append(message);
    }

    @Override
    public Locale getPlayerLocale(UUID uniqueId) {
        Player player = Bukkit.getPlayer(uniqueId);
        return player != null ? player.locale() : defaultLocale;
    }

    // --- Audience-based API ---
    @Override
    public void sendMessage(Audience audience, Component message) {
        if (audience != null && message != null) {
            audience.sendMessage(message);
        }
    }

    @Override
    public void sendStyledMessage(Audience audience, MessageStyle style, String translationKey, Object... args) {
        sendMessage(audience, getStyledMessage(audience, style, translationKey, args));
    }

    @Override
    public void sendGenericResponse(Audience audience, GenericResponse response) {
        sendStyledMessage(audience, MessageStyle.ERROR, response.getKey());
    }

    @Override
    public Component getStyledMessage(Audience audience, MessageStyle style, String translationKey, Object... args) {
        Locale locale = defaultLocale;
        // Try to get locale from Player if possible
        if (audience instanceof org.bukkit.entity.Player player) {
            locale = player.locale();
        }
        return getStyledMessage(locale, style, translationKey, args);
    }

    @Override
    public void sendStyledMessageWithTags(Audience audience, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        sendMessage(audience, getStyledMessageWithTags(audience, style, translationKey, tags, args));
    }

    @Override
    public Component getStyledMessageWithTags(Audience audience, MessageStyle style, String translationKey, List<MessageTag> tags, Object... args) {
        Locale locale = defaultLocale;
        if (audience instanceof org.bukkit.entity.Player player) {
            locale = player.locale();
        }
        return getStyledMessageWithTags(locale, style, translationKey, tags, args);
    }

    @Override
    public void setTagFormatter(TagFormatter formatter) {
        this.tagFormatter = formatter;
    }

    @Override
    public Component getStyledMessage(Locale locale, MessageStyle style, String translationKey, Object... args) {
        String raw = getTranslation(translationKey, locale);
        // Replace placeholders with colored arguments and add style prefix
        StringBuilder sb = new StringBuilder();
        sb.append(style.getPrefix());
        String result = raw;
        int argCount = args != null ? args.length : 0;
        String argumentColor = style.getArgumentColorTag();
        for (int i = 0; i < argCount; i++) {
            String placeholder = "{arg" + (i + 1) + "}";
            String coloredArg = argumentColor.isEmpty() ? String.valueOf(args[i]) : argumentColor + args[i] + "</" + argumentColor.substring(1);
            result = result.replace(placeholder, coloredArg);
        }
        sb.append(result);
        return deserialize(sb.toString());
    }
}