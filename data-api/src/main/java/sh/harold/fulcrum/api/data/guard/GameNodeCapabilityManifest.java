package sh.harold.fulcrum.api.data.guard;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Source-controlled negative capability manifest shipped by game-node artifacts.
 */
public final class GameNodeCapabilityManifest {
    public static final String RESOURCE_PATH = "META-INF/fulcrum/game-node-negative-capabilities.properties";

    private final int version;
    private final GameNodeStorageGuard.NodeKind nodeKind;
    private final boolean forbidLocalAuthority;
    private final boolean forbidDirectStoreConfig;
    private final Set<String> forbiddenCapabilities;
    private final int commandSchemaVersion;
    private final String commandContractFingerprint;
    private final int readSchemaVersion;
    private final String readContractFingerprint;

    private GameNodeCapabilityManifest(
        int version,
        GameNodeStorageGuard.NodeKind nodeKind,
        boolean forbidLocalAuthority,
        boolean forbidDirectStoreConfig,
        Set<String> forbiddenCapabilities,
        int commandSchemaVersion,
        String commandContractFingerprint,
        int readSchemaVersion,
        String readContractFingerprint
    ) {
        this.version = version;
        this.nodeKind = Objects.requireNonNull(nodeKind, "nodeKind");
        this.forbidLocalAuthority = forbidLocalAuthority;
        this.forbidDirectStoreConfig = forbidDirectStoreConfig;
        this.forbiddenCapabilities = Set.copyOf(forbiddenCapabilities);
        this.commandSchemaVersion = commandSchemaVersion;
        this.commandContractFingerprint = normalizeFingerprint(
            commandContractFingerprint,
            "data-authority.command-contract-fingerprint"
        );
        this.readSchemaVersion = readSchemaVersion;
        this.readContractFingerprint = normalizeFingerprint(
            readContractFingerprint,
            "data-authority.read-contract-fingerprint"
        );
        if (version <= 0) {
            throw new IllegalArgumentException("manifest.version must be positive");
        }
        if (this.forbiddenCapabilities.isEmpty()) {
            throw new IllegalArgumentException("forbidden-capabilities must not be empty");
        }
        if (commandSchemaVersion <= 0) {
            throw new IllegalArgumentException("data-authority.command-schema-version must be positive");
        }
        if (readSchemaVersion <= 0) {
            throw new IllegalArgumentException("data-authority.read-schema-version must be positive");
        }
    }

    public static GameNodeCapabilityManifest loadDefault(
        GameNodeStorageGuard.NodeKind expectedNodeKind,
        ClassLoader classLoader
    ) {
        Objects.requireNonNull(expectedNodeKind, "expectedNodeKind");
        ClassLoader effectiveLoader = classLoader == null
            ? GameNodeCapabilityManifest.class.getClassLoader()
            : classLoader;
        try (InputStream stream = effectiveLoader.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Missing game-node negative capability manifest: " + RESOURCE_PATH);
            }
            GameNodeCapabilityManifest manifest = load(stream);
            manifest.requireNodeKind(expectedNodeKind);
            return manifest;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load game-node negative capability manifest", exception);
        }
    }

    public static GameNodeCapabilityManifest load(InputStream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            Properties properties = new Properties();
            properties.load(stream);
            return new GameNodeCapabilityManifest(
                Integer.parseInt(required(properties, "manifest.version")),
                GameNodeStorageGuard.NodeKind.valueOf(required(properties, "node-kind").toUpperCase(Locale.ROOT)),
                booleanValue(properties, "forbid-local-authority"),
                booleanValue(properties, "forbid-direct-store-config"),
                csv(properties.getProperty("forbidden-capabilities")),
                Integer.parseInt(required(properties, "data-authority.command-schema-version")),
                required(properties, "data-authority.command-contract-fingerprint"),
                Integer.parseInt(required(properties, "data-authority.read-schema-version")),
                required(properties, "data-authority.read-contract-fingerprint")
            );
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse game-node negative capability manifest", exception);
        }
    }

    public void requireNodeKind(GameNodeStorageGuard.NodeKind expectedNodeKind) {
        if (nodeKind != expectedNodeKind) {
            throw new IllegalStateException(
                "Game-node negative capability manifest is for " + nodeKind.displayName()
                    + " but was loaded by " + expectedNodeKind.displayName()
            );
        }
    }

    public void requireAllowedConfig(Map<String, Object> config) {
        Map<String, Object> safeConfig = config == null ? Map.of() : config;
        if (forbidLocalAuthority && "local".equalsIgnoreCase(stringValue(safeConfig, "authority.mode"))) {
            throw new IllegalStateException(
                "P3 no-store violation for " + nodeKind.displayName()
                    + " game node. Negative capability manifest forbids authority.mode=local."
            );
        }
        if (forbidDirectStoreConfig) {
            GameNodeStorageGuard.requireNoStoreGameNode(nodeKind, safeConfig);
        }
    }

    public int version() {
        return version;
    }

    public GameNodeStorageGuard.NodeKind nodeKind() {
        return nodeKind;
    }

    public boolean forbidLocalAuthority() {
        return forbidLocalAuthority;
    }

    public boolean forbidDirectStoreConfig() {
        return forbidDirectStoreConfig;
    }

    public Set<String> forbiddenCapabilities() {
        return forbiddenCapabilities;
    }

    public int commandSchemaVersion() {
        return commandSchemaVersion;
    }

    public String commandContractFingerprint() {
        return commandContractFingerprint;
    }

    public int readSchemaVersion() {
        return readSchemaVersion;
    }

    public String readContractFingerprint() {
        return readContractFingerprint;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing game-node capability manifest property: " + key);
        }
        return value.trim();
    }

    private static boolean booleanValue(Properties properties, String key) {
        return Boolean.parseBoolean(required(properties, key));
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return Set.copyOf(values);
    }

    private static String normalizeFingerprint(String value, String key) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(key + " must be a 64-character SHA-256 fingerprint");
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static String stringValue(Map<String, Object> config, String path) {
        Object current = config;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(part);
        }
        return current == null ? "" : current.toString();
    }
}
