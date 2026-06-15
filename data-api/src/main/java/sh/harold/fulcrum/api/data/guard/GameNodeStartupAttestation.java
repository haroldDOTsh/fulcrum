package sh.harold.fulcrum.api.data.guard;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Effective startup proof that a game-node artifact has no direct store capability.
 */
public final class GameNodeStartupAttestation {
    private static final List<ResourceProbe> DEFAULT_RESOURCE_PROBES = List.of(
        new ResourceProbe(
            "driver.jdbc.sql",
            "org/postgresql/Driver.class",
            "game-node classpath must not expose the PostgreSQL JDBC driver"
        ),
        new ResourceProbe(
            "pool.direct.sql",
            "com/zaxxer/hikari/HikariDataSource.class",
            "game-node classpath must not expose the Hikari direct database pool"
        ),
        new ResourceProbe(
            "store.direct.document",
            "com/mongodb/MongoClientSettings.class",
            "game-node classpath must not expose the MongoDB direct document store driver"
        )
    );
    private static final String COMMAND_MESSAGE_BUS_CAPABILITY = "authority.command.message-bus";

    private GameNodeStartupAttestation() {
    }

    /**
     * Verifies startup capabilities using the default classpath probes.
     *
     * @param manifest negative capability manifest
     * @param config effective runtime data config
     * @param classLoader classloader to inspect for forbidden resources
     * @return successful attestation report
     */
    public static Report require(
        GameNodeCapabilityManifest manifest,
        Map<String, Object> config,
        ClassLoader classLoader
    ) {
        return require(manifest, config, classLoader, DEFAULT_RESOURCE_PROBES);
    }

    /**
     * Verifies startup capabilities using an explicit probe set.
     *
     * @param manifest negative capability manifest
     * @param config effective runtime data config
     * @param classLoader classloader to inspect for forbidden resources
     * @param resourceProbes resources that prove forbidden capabilities when present
     * @return successful attestation report
     */
    public static Report require(
        GameNodeCapabilityManifest manifest,
        Map<String, Object> config,
        ClassLoader classLoader,
        Collection<ResourceProbe> resourceProbes
    ) {
        Report report = inspect(manifest, config, classLoader, resourceProbes);
        if (!report.passed()) {
            throw new IllegalStateException(report.failureMessage());
        }
        return report;
    }

    /**
     * Inspects startup capabilities without throwing.
     *
     * @param manifest negative capability manifest
     * @param config effective runtime data config
     * @param classLoader classloader to inspect for forbidden resources
     * @param resourceProbes resources that prove forbidden capabilities when present
     * @return attestation report
     */
    public static Report inspect(
        GameNodeCapabilityManifest manifest,
        Map<String, Object> config,
        ClassLoader classLoader,
        Collection<ResourceProbe> resourceProbes
    ) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, Object> safeConfig = config == null ? Map.of() : Map.copyOf(config);
        ClassLoader effectiveLoader = classLoader == null
            ? GameNodeStartupAttestation.class.getClassLoader()
            : classLoader;
        List<ResourceProbe> probes = normalizedProbes(resourceProbes);

        List<Violation> violations = new ArrayList<>();
        if (manifest.forbidLocalAuthority()
            && "local".equalsIgnoreCase(stringValue(safeConfig, "authority.mode"))) {
            violations.add(new Violation(
                "config",
                "authority.mode",
                "negative capability manifest forbids authority.mode=local on game nodes"
            ));
        }
        if (manifest.forbiddenCapabilities().contains(COMMAND_MESSAGE_BUS_CAPABILITY)) {
            String commandTransport = stringValue(safeConfig, "authority.command-transport");
            if (!commandTransport.isBlank()
                && !"kafka".equalsIgnoreCase(commandTransport)
                && !"log".equalsIgnoreCase(commandTransport)) {
                violations.add(new Violation(
                    "config",
                    "authority.command-transport",
                    "game-node authority commands must use the durable Kafka command log"
                ));
            }
        }
        if (manifest.forbidDirectStoreConfig()) {
            for (GameNodeStorageGuard.Violation violation :
                GameNodeStorageGuard.inspectNoStoreGameNode(manifest.nodeKind(), safeConfig)) {
                violations.add(new Violation("config", violation.path(), violation.reason()));
            }
        }
        if (manifest.commandSchemaVersion() != DataAuthority.COMMAND_SCHEMA_VERSION) {
            violations.add(new Violation(
                "contract",
                "data-authority.command-schema-version",
                "game-node manifest expects command schema version " + manifest.commandSchemaVersion()
                    + " but executable contract is " + DataAuthority.COMMAND_SCHEMA_VERSION
            ));
        }
        if (!manifest.commandContractFingerprint().equals(DataAuthorityCommandContracts.fingerprint())) {
            violations.add(new Violation(
                "contract",
                "data-authority.command-contract-fingerprint",
                "game-node manifest expects command contract " + shortFingerprint(manifest.commandContractFingerprint())
                    + " but executable contract is " + shortFingerprint(DataAuthorityCommandContracts.fingerprint())
            ));
        }
        if (manifest.readSchemaVersion() != DataAuthorityReadContracts.schemaVersion()) {
            violations.add(new Violation(
                "contract",
                "data-authority.read-schema-version",
                "game-node manifest expects read schema version " + manifest.readSchemaVersion()
                    + " but executable contract is " + DataAuthorityReadContracts.schemaVersion()
            ));
        }
        if (!manifest.readContractFingerprint().equals(DataAuthorityReadContracts.fingerprint())) {
            violations.add(new Violation(
                "contract",
                "data-authority.read-contract-fingerprint",
                "game-node manifest expects read contract " + shortFingerprint(manifest.readContractFingerprint())
                    + " but executable contract is " + shortFingerprint(DataAuthorityReadContracts.fingerprint())
            ));
        }

        List<ResourceProbeResult> probeResults = new ArrayList<>();
        Set<String> forbiddenCapabilities = manifest.forbiddenCapabilities();
        for (ResourceProbe probe : probes) {
            if (!forbiddenCapabilities.contains(probe.capability())) {
                continue;
            }
            boolean present = effectiveLoader.getResource(probe.resourcePath()) != null;
            probeResults.add(new ResourceProbeResult(
                probe.capability(),
                probe.resourcePath(),
                present,
                probe.reason()
            ));
            if (present) {
                violations.add(new Violation("classpath", probe.resourcePath(), probe.reason()));
            }
        }

        return new Report(
            manifest,
            violations.isEmpty(),
            configFingerprint(safeConfig),
            classpathFingerprint(probeResults),
            violations,
            probeResults
        );
    }

    /**
     * Default forbidden classpath probes used by game-node startup checks.
     *
     * @return default resource probes
     */
    public static List<ResourceProbe> defaultResourceProbes() {
        return DEFAULT_RESOURCE_PROBES;
    }

    private static List<ResourceProbe> normalizedProbes(Collection<ResourceProbe> probes) {
        Collection<ResourceProbe> safeProbes = probes == null ? List.of() : probes;
        List<ResourceProbe> normalized = new ArrayList<>();
        for (ResourceProbe probe : safeProbes) {
            if (probe != null) {
                normalized.add(probe);
            }
        }
        normalized.sort(Comparator
            .comparing(ResourceProbe::capability)
            .thenComparing(ResourceProbe::resourcePath));
        return List.copyOf(normalized);
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

    private static String configFingerprint(Map<String, Object> config) {
        Map<String, Object> flattened = new TreeMap<>();
        flatten("", config, flattened);
        StringBuilder material = new StringBuilder();
        for (Map.Entry<String, Object> entry : flattened.entrySet()) {
            material.append(entry.getKey())
                .append('=')
                .append(sanitizedValue(entry.getKey(), entry.getValue()))
                .append('\n');
        }
        return sha256(material.toString());
    }

    private static String classpathFingerprint(List<ResourceProbeResult> probeResults) {
        StringBuilder material = new StringBuilder();
        for (ResourceProbeResult result : probeResults.stream()
            .sorted(Comparator
                .comparing(ResourceProbeResult::capability)
                .thenComparing(ResourceProbeResult::resourcePath))
            .toList()) {
            material.append(result.capability())
                .append('|')
                .append(result.resourcePath())
                .append('|')
                .append(result.present())
                .append('\n');
        }
        return sha256(material.toString());
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> flattened) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String path = prefix.isBlank() ? entry.getKey().toString() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            flattened.put(path, value);
            if (value instanceof Map<?, ?> nested) {
                flatten(path, nested, flattened);
            }
        }
    }

    private static String sanitizedValue(String path, Object value) {
        if (value == null) {
            return "null";
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("password")
            || lowerPath.contains("secret")
            || lowerPath.contains("token")
            || lowerPath.contains("credential")
            || lowerPath.endsWith(".key")
            || lowerPath.contains("api-key")) {
            return "<redacted>";
        }
        if (value instanceof Map<?, ?> map) {
            return "map:" + map.size();
        }
        return value.toString();
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to calculate startup attestation fingerprint", exception);
        }
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    /**
     * Resource that proves a forbidden capability when visible to the game-node classloader.
     *
     * @param capability forbidden capability id from the manifest
     * @param resourcePath classpath resource path
     * @param reason operator-facing failure reason
     */
    public record ResourceProbe(String capability, String resourcePath, String reason) {
        public ResourceProbe {
            capability = normalize(capability, "capability");
            resourcePath = normalize(resourcePath, "resourcePath");
            reason = normalize(reason, "reason");
        }
    }

    /**
     * Outcome for one classpath probe.
     *
     * @param capability forbidden capability id
     * @param resourcePath probed classpath resource
     * @param present whether the resource was visible
     * @param reason operator-facing reason
     */
    public record ResourceProbeResult(String capability, String resourcePath, boolean present, String reason) {
        public ResourceProbeResult {
            capability = normalize(capability, "capability");
            resourcePath = normalize(resourcePath, "resourcePath");
            reason = normalize(reason, "reason");
        }
    }

    /**
     * Startup attestation violation.
     *
     * @param source violation source
     * @param path config path or resource path
     * @param reason operator-facing reason
     */
    public record Violation(String source, String path, String reason) {
        public Violation {
            source = normalize(source, "source");
            path = normalize(path, "path");
            reason = normalize(reason, "reason");
        }
    }

    /**
     * Deterministic startup attestation report.
     *
     * @param manifest manifest used for the check
     * @param passed whether no violations were found
     * @param configFingerprint sanitized effective config fingerprint
     * @param classpathFingerprint classpath probe fingerprint
     * @param violations detected violations
     * @param resourceProbes evaluated resource probes
     */
    public record Report(
        GameNodeCapabilityManifest manifest,
        boolean passed,
        String configFingerprint,
        String classpathFingerprint,
        List<Violation> violations,
        List<ResourceProbeResult> resourceProbes
    ) {
        public Report {
            manifest = Objects.requireNonNull(manifest, "manifest");
            configFingerprint = normalize(configFingerprint, "configFingerprint");
            classpathFingerprint = normalize(classpathFingerprint, "classpathFingerprint");
            violations = violations == null ? List.of() : List.copyOf(violations);
            resourceProbes = resourceProbes == null ? List.of() : List.copyOf(resourceProbes);
        }

        /**
         * Short stable line suitable for startup logs.
         *
         * @return attestation summary
         */
        public String summary() {
            return "nodeKind=" + manifest.nodeKind().displayName()
                + ", manifestVersion=" + manifest.version()
                + ", passed=" + passed
                + ", commandContract=" + shortFingerprint(manifest.commandContractFingerprint())
                + ", readContract=" + shortFingerprint(manifest.readContractFingerprint())
                + ", configFingerprint=" + configFingerprint
                + ", classpathFingerprint=" + classpathFingerprint
                + ", attestationFingerprint=" + attestationFingerprint();
        }

        /**
         * Stable fingerprint over the manifest, sanitized config, classpath probes, and denial evidence.
         *
         * @return attestation fingerprint
         */
        public String attestationFingerprint() {
            StringBuilder material = new StringBuilder()
                .append("nodeKind=").append(manifest.nodeKind().name()).append('\n')
                .append("manifestVersion=").append(manifest.version()).append('\n')
                .append("forbidLocalAuthority=").append(manifest.forbidLocalAuthority()).append('\n')
                .append("forbidDirectStoreConfig=").append(manifest.forbidDirectStoreConfig()).append('\n')
                .append("commandSchemaVersion=").append(manifest.commandSchemaVersion()).append('\n')
                .append("commandContractFingerprint=").append(manifest.commandContractFingerprint()).append('\n')
                .append("readSchemaVersion=").append(manifest.readSchemaVersion()).append('\n')
                .append("readContractFingerprint=").append(manifest.readContractFingerprint()).append('\n')
                .append("forbiddenCapabilities=");
            manifest.forbiddenCapabilities().stream()
                .sorted()
                .forEach(capability -> material.append(capability).append(','));
            material.append('\n')
                .append("passed=").append(passed).append('\n')
                .append("configFingerprint=").append(configFingerprint).append('\n')
                .append("classpathFingerprint=").append(classpathFingerprint).append('\n');

            violations.stream()
                .sorted(Comparator
                    .comparing(Violation::source)
                    .thenComparing(Violation::path)
                    .thenComparing(Violation::reason))
                .forEach(violation -> material
                    .append("violation=")
                    .append(violation.source()).append('|')
                    .append(violation.path()).append('|')
                    .append(violation.reason()).append('\n'));
            resourceProbes.stream()
                .sorted(Comparator
                    .comparing(ResourceProbeResult::capability)
                    .thenComparing(ResourceProbeResult::resourcePath))
                .forEach(probe -> material
                    .append("probe=")
                    .append(probe.capability()).append('|')
                    .append(probe.resourcePath()).append('|')
                    .append(probe.present()).append('|')
                    .append(probe.reason()).append('\n'));
            return sha256(material.toString());
        }

        private String failureMessage() {
            StringBuilder builder = new StringBuilder();
            builder.append("P3 no-store violation startup attestation failed for ")
                .append(manifest.nodeKind().displayName())
                .append(" game node (denialFingerprint=")
                .append(attestationFingerprint())
                .append("):");
            for (Violation violation : violations) {
                builder.append(System.lineSeparator())
                    .append("- ")
                    .append(violation.source())
                    .append(' ')
                    .append(violation.path())
                    .append(": ")
                    .append(violation.reason());
            }
            return builder.toString();
        }
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
