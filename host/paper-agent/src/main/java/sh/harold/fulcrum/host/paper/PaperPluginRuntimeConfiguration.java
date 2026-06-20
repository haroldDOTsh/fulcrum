package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PaperPluginRuntimeConfiguration(
        HostSecurityContext securityContext,
        SessionId sessionId,
        Path allocatedAssignmentFile,
        String routeIdPrefix,
        PaperSpawnPoint spawnPoint,
        Optional<URI> observationBridgeUrl,
        Optional<URI> capabilityBridgeUrl,
        Optional<URI> rewardBridgeUrl,
        Optional<Path> contributionBundleDirectory) {
    public PaperPluginRuntimeConfiguration {
        securityContext = Objects.requireNonNull(securityContext, "securityContext");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        allocatedAssignmentFile = Objects.requireNonNull(allocatedAssignmentFile, "allocatedAssignmentFile");
        routeIdPrefix = PaperArtifactNames.requireNonBlank(routeIdPrefix, "routeIdPrefix");
        spawnPoint = Objects.requireNonNull(spawnPoint, "spawnPoint");
        observationBridgeUrl = Objects.requireNonNull(observationBridgeUrl, "observationBridgeUrl");
        capabilityBridgeUrl = Objects.requireNonNull(capabilityBridgeUrl, "capabilityBridgeUrl");
        rewardBridgeUrl = Objects.requireNonNull(rewardBridgeUrl, "rewardBridgeUrl");
        contributionBundleDirectory = Objects.requireNonNull(
                contributionBundleDirectory,
                "contributionBundleDirectory")
                .map(path -> path.toAbsolutePath().normalize());
    }

    public static PaperPluginRuntimeConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId(required(environment, "FULCRUM_INSTANCE_ID")),
                HostInstanceKinds.PAPER,
                new PoolId(required(environment, "FULCRUM_POOL_ID")),
                new MachineRef(required(environment, "FULCRUM_MACHINE_REF")),
                new PrincipalId(required(environment, "FULCRUM_PRINCIPAL_ID")));
        HostSecurityContext securityContext = new HostSecurityContext(
                identity,
                optional(environment, "FULCRUM_CREDENTIAL_REF", "service-account:paper-agent"),
                HostCredentialScope.of(
                        new HostResourceGrant(
                                HostResourceFamily.TOPIC,
                                HostAccessMode.PRODUCE,
                                optional(environment, "FULCRUM_HOST_OBSERVATION_TOPIC", "host.observation"))));
        return new PaperPluginRuntimeConfiguration(
                securityContext,
                new SessionId(required(environment, "FULCRUM_PAPER_SESSION_ID")),
                allocatedAssignmentFile(environment),
                optional(environment, "FULCRUM_PAPER_ROUTE_ID_PREFIX", "route-velocity-login-"),
                new PaperSpawnPoint(
                        optional(environment, "FULCRUM_PAPER_SPAWN_WORLD", "world"),
                        doubleValue(environment, "FULCRUM_PAPER_SPAWN_X", 0.5D),
                        doubleValue(environment, "FULCRUM_PAPER_SPAWN_Y", 65.0D),
                        doubleValue(environment, "FULCRUM_PAPER_SPAWN_Z", 0.5D),
                        floatValue(environment, "FULCRUM_PAPER_SPAWN_YAW", 0.0F),
                        floatValue(environment, "FULCRUM_PAPER_SPAWN_PITCH", 0.0F)),
                optionalHttpUri(environment, "FULCRUM_PAPER_OBSERVATION_BRIDGE_URL"),
                optionalHttpUriWithExplicitPort(environment, "FULCRUM_PAPER_CAPABILITY_BRIDGE_URL"),
                optionalHttpUriWithExplicitPort(environment, "FULCRUM_PAPER_REWARD_BRIDGE_URL"),
                optionalPath(environment, "FULCRUM_PAPER_CONTRIBUTION_BUNDLE_DIR"));
    }

    private static Path allocatedAssignmentFile(Map<String, String> environment) {
        String configured = environment.get("FULCRUM_PAPER_ALLOCATION_FILE");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return PaperAllocatedAssignmentFile.defaultPath(Path.of(optional(
                environment,
                "FULCRUM_PAPER_SERVER_ROOT",
                "/var/fulcrum/paper")));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required Paper runtime binding " + name);
        }
        return value.trim();
    }

    private static String optional(Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return PaperArtifactNames.requireNonBlank(defaultValue, name);
        }
        return value.trim();
    }

    private static Optional<URI> optionalHttpUri(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(name + " must be a valid URI", exception);
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(name + " must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(name + " must include a host");
        }
        return Optional.of(uri);
    }

    private static Optional<URI> optionalHttpUriWithExplicitPort(Map<String, String> environment, String name) {
        Optional<URI> uri = optionalHttpUri(environment, name);
        uri.ifPresent(value -> {
            if (value.getPort() < 1 || value.getPort() > 65_535) {
                throw new IllegalArgumentException(name + " must include an explicit port between 1 and 65535");
            }
        });
        return uri;
    }

    private static Optional<Path> optionalPath(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value.trim()));
    }

    private static double doubleValue(Map<String, String> environment, String name, double defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static float floatValue(Map<String, String> environment, String name, float defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }
}
