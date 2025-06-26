package sh.harold.fulcrum.message.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Utility class for managing translation files and namespacing.
 * Handles creation of language files and proper YAML structure.
 */
public class TranslationUtil {
    
    private static final Map<String, Map<Locale, Map<String, Object>>> loadedTranslations = 
        new ConcurrentHashMap<>();
    
    /**
     * Parses a translation key into its feature and path components.
     * Example: "fairy_soul.yeet.test.test" -> Feature: "fairy_soul", Path: ["yeet", "test", "test"]
     * 
     * @param translationKey The full translation key
     * @return A TranslationKey object containing the parsed components
     */
    public static TranslationKey parseKey(String translationKey) {
        String[] parts = translationKey.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Translation key must have at least feature.key format: " + translationKey);
        }
        
        String feature = parts[0];
        String[] pathParts = new String[parts.length - 1];
        System.arraycopy(parts, 1, pathParts, 0, parts.length - 1);
        
        return new TranslationKey(feature, pathParts);
    }
    
    /**
     * Gets the file path for a translation file.
     * 
     * @param baseDir The base language directory
     * @param feature The feature name
     * @param locale The locale
     * @return The file path for the translation file
     */
    public static File getTranslationFile(File baseDir, String feature, Locale locale) {
        File featureDir = new File(baseDir, feature);
        String localeString = locale.getLanguage().toLowerCase() + "_" + locale.getCountry().toLowerCase();
        return new File(featureDir, localeString + ".yml");
    }
    
    /**
     * Creates a translation file with a placeholder if it doesn't exist.
     * 
     * @param file The translation file to create
     * @param translationKey The translation key to add
     * @throws IOException If file creation fails
     */
    public static void createTranslationFileIfNeeded(File file, TranslationKey translationKey) throws IOException {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            
            // Create the nested YAML structure
            Map<String, Object> yamlStructure = new LinkedHashMap<>();
            Map<String, Object> current = yamlStructure;
            
            // Navigate through the path, creating nested maps
            for (int i = 0; i < translationKey.path().length - 1; i++) {
                String part = translationKey.path()[i];
                Map<String, Object> nextLevel = new LinkedHashMap<>();
                current.put(part, nextLevel);
                current = nextLevel;
            }
            
            // Add the final key with a placeholder
            String finalKey = translationKey.path()[translationKey.path().length - 1];
            current.put(finalKey, "PLACEHOLDER: " + String.join(".", translationKey.path()));
            
            // Write the YAML file
            DumperOptions options = new DumperOptions();
            options.setIndent(2);
            options.setPrettyFlow(true);
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            
            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(file)) {
                yaml.dump(yamlStructure, writer);
            }
        }
    }
    
    /**
     * Loads a translation from the file system.
     * 
     * @param baseDir The base language directory
     * @param feature The feature name
     * @param locale The locale
     * @param translationKey The translation key
     * @return The translation string, or null if not found
     */
    public static String loadTranslation(File baseDir, String feature, Locale locale, TranslationKey translationKey) {
        String cacheKey = feature + ":" + locale.toString();
        
        // Check cache first
        Map<Locale, Map<String, Object>> featureTranslations = loadedTranslations.get(feature);
        if (featureTranslations != null) {
            Map<String, Object> localeTranslations = featureTranslations.get(locale);
            if (localeTranslations != null) {
                return getNestedValue(localeTranslations, translationKey.path());
            }
        }
        
        // Load from file
        File translationFile = getTranslationFile(baseDir, feature, locale);
        if (!translationFile.exists()) {
            try {
                createTranslationFileIfNeeded(translationFile, translationKey);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(translationFile.toPath()));
            
            // Cache the loaded data
            loadedTranslations.computeIfAbsent(feature, k -> new ConcurrentHashMap<>())
                            .put(locale, data != null ? data : new HashMap<>());
            
            return getNestedValue(data, translationKey.path());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Gets a nested value from a map using a path array.
     * 
     * @param map The map to search in
     * @param path The path array
     * @return The value as a string, or null if not found
     */
    @SuppressWarnings("unchecked")
    private static String getNestedValue(Map<String, Object> map, String[] path) {
        if (map == null || path.length == 0) {
            return null;
        }
        
        Object current = map;
        for (String part : path) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        
        return current != null ? current.toString() : null;
    }
    
    /**
     * Clears the translation cache.
     */
    public static void clearCache() {
        loadedTranslations.clear();
    }

    /**
         * Represents a parsed translation key.
         */
        public record TranslationKey(String feature, String[] path) {

        public String getFullKey() {
                return feature + "." + String.join(".", path);
            }
        }
}
