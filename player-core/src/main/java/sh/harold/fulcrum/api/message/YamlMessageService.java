package sh.harold.fulcrum.api.message;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class YamlMessageService implements MessageService {

    private final Path langDirectory;
    // Structure: <Feature, <Locale, Config>>
    private final Map<String, Map<Locale, FileConfiguration>> translations = new HashMap<>();
    private final Locale defaultLocale = Locale.US;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private TagFormatter tagFormatter = new DefaultTagFormatter();

    public YamlMessageService(Path pluginDataFolder) {
        this.langDirectory = pluginDataFolder.resolve("lang");
        try {
            if (Files.notExists(langDirectory)) {
                Files.createDirectories(langDirectory);
            }
            saveDefaultLanguageFile();
            loadTranslations();
        } catch (IOException e) {
            Bukkit.getLogger().severe("Failed to create or load language directory: " + e.getMessage());
        }
    }

    private void saveDefaultLanguageFile() throws IOException {
        Path genericLangDir = langDirectory.resolve("generic");
        if (Files.notExists(genericLangDir)) {
            Files.createDirectories(genericLangDir);
        }

        Path defaultFile = genericLangDir.resolve("en_US.yml");
        if (Files.notExists(defaultFile)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang/generic/en_US.yml")) {
                if (in != null) {
                    Files.copy(in, defaultFile);
                } else {
                    // Create a default file with a sample key if resource is missing
                    FileConfiguration config = new YamlConfiguration();
                    config.set("error", "&cAn error occurred.");
                    config.set("no_permission", "&cYou do not have permission to do that.");
                    config.set("on_cooldown", "&cYou are on cooldown for %s seconds.");
                    config.save(defaultFile.toFile());
                }
            }
        }
    }

    private void loadTranslations() {
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
        if (featureTranslations == null) {
            return key; // Feature not found
        }

        FileConfiguration config = featureTranslations.getOrDefault(locale, featureTranslations.get(defaultLocale));
        if (config != null) {
            return config.getString(path, key);
        }

        return key;
    }

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
}