package sh.harold.fulcrum.validation.auctionescrow;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record AuctionEscrowBackendConfig(
        HostSecurityContext securityContext,
        String bundleDigest,
        String authorityDomain,
        String resourceClass,
        String contractName,
        String kafkaBootstrapServers,
        String commandTopic,
        String eventTopic,
        String stateTopic,
        String responseTopic,
        String consumerGroup,
        String postgresJdbcUrl,
        String postgresUsername,
        String postgresPassword,
        String cassandraContactPoints,
        String cassandraLocalDatacenter,
        String valkeyEndpoint,
        long replayWatermark,
        Optional<URI> registrationEndpoint,
        String readyFile,
        StartupMode startupMode) {
    public AuctionEscrowBackendConfig {
        securityContext = Objects.requireNonNull(securityContext, "securityContext");
        bundleDigest = requireNonBlank(bundleDigest, "bundleDigest");
        authorityDomain = requireExpected(
                authorityDomain,
                "authorityDomain",
                AuctionEscrowAuthority.AUTHORITY_DOMAIN);
        resourceClass = requireExpected(
                resourceClass,
                "resourceClass",
                AuctionEscrowAuthority.RESOURCE_CLASS);
        contractName = requireExpected(contractName, "contractName", AuctionEscrowAuthority.CONTRACT.value());
        kafkaBootstrapServers = requireNonBlank(kafkaBootstrapServers, "kafkaBootstrapServers");
        commandTopic = requireNonBlank(commandTopic, "commandTopic");
        eventTopic = requireNonBlank(eventTopic, "eventTopic");
        stateTopic = requireNonBlank(stateTopic, "stateTopic");
        responseTopic = requireNonBlank(responseTopic, "responseTopic");
        consumerGroup = requireNonBlank(consumerGroup, "consumerGroup");
        postgresJdbcUrl = requireNonBlank(postgresJdbcUrl, "postgresJdbcUrl");
        postgresUsername = requireNonBlank(postgresUsername, "postgresUsername");
        postgresPassword = requireNonBlank(postgresPassword, "postgresPassword");
        cassandraContactPoints = requireNonBlank(cassandraContactPoints, "cassandraContactPoints");
        cassandraLocalDatacenter = requireNonBlank(cassandraLocalDatacenter, "cassandraLocalDatacenter");
        valkeyEndpoint = requireNonBlank(valkeyEndpoint, "valkeyEndpoint");
        if (replayWatermark < 0) {
            throw new IllegalArgumentException("replayWatermark must be non-negative");
        }
        registrationEndpoint = registrationEndpoint == null ? Optional.empty() : registrationEndpoint;
        readyFile = requireNonBlank(readyFile, "readyFile");
        startupMode = Objects.requireNonNull(startupMode, "startupMode");
    }

    public static AuctionEscrowBackendConfig fromEnvironment() {
        return from(System.getenv());
    }

    static AuctionEscrowBackendConfig from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        HostInstanceIdentity identity = new HostInstanceIdentity(
                new InstanceId(required(environment, "FULCRUM_INSTANCE_ID")),
                required(environment, "FULCRUM_INSTANCE_KIND"),
                new PoolId(required(environment, "FULCRUM_POOL_ID")),
                new MachineRef(required(environment, "FULCRUM_MACHINE_REF")),
                new PrincipalId(required(environment, "FULCRUM_PRINCIPAL_ID")));
        HostSecurityContext securityContext = new HostSecurityContext(
                identity,
                required(environment, "FULCRUM_CREDENTIAL_REF"),
                HostCredentialScope.of(
                        AuthorityBackendGrants.authorityDomain(AuctionEscrowAuthority.AUTHORITY_DOMAIN),
                        AuthorityBackendGrants.resourceClass(AuctionEscrowAuthority.RESOURCE_CLASS)));
        return new AuctionEscrowBackendConfig(
                securityContext,
                required(environment, "FULCRUM_ESCROW_BUNDLE_DIGEST"),
                required(environment, "FULCRUM_AUTHORITY_DOMAIN"),
                required(environment, "FULCRUM_AUTHORITY_RESOURCE_CLASS"),
                required(environment, "FULCRUM_ESCROW_CONTRACT_NAME"),
                required(environment, "FULCRUM_KAFKA_BOOTSTRAP_SERVERS"),
                required(environment, "FULCRUM_ESCROW_COMMAND_TOPIC"),
                required(environment, "FULCRUM_ESCROW_EVENT_TOPIC"),
                required(environment, "FULCRUM_ESCROW_STATE_TOPIC"),
                required(environment, "FULCRUM_ESCROW_RESPONSE_TOPIC"),
                required(environment, "FULCRUM_ESCROW_CONSUMER_GROUP"),
                required(environment, "FULCRUM_POSTGRES_JDBC_URL"),
                required(environment, "FULCRUM_POSTGRES_USERNAME"),
                required(environment, "FULCRUM_POSTGRES_PASSWORD"),
                required(environment, "FULCRUM_CASSANDRA_CONTACT_POINTS"),
                required(environment, "FULCRUM_CASSANDRA_LOCAL_DATACENTER"),
                required(environment, "FULCRUM_VALKEY_ENDPOINT"),
                replayWatermark(environment),
                registrationEndpoint(environment.get("FULCRUM_REGISTRATION_ENDPOINT")),
                environment.getOrDefault("FULCRUM_ESCROW_READY_FILE", "/var/run/fulcrum/auction-escrow.ready"),
                StartupMode.from(environment.getOrDefault("FULCRUM_ESCROW_STARTUP_MODE", "serve")));
    }

    public AuthorityBackendRegistrationRequest registrationRequest(Instant requestedAt) {
        return AuthorityBackendRegistrationRequest.credentialed(
                AuctionEscrowAuthority.descriptor(),
                securityContext,
                bundleDigest,
                AuthorityArtifactVerificationEvidence.verified(
                        "OCI",
                        "oci://ghcr.io/harolddotsh/auction-escrow-backend@sha256:" + bundleDigest,
                        bundleDigest,
                        "cosign:test"),
                requestedAt);
    }

    String bootSummary() {
        return "principal=" + securityContext.identity().principalId().value()
                + "|authorityDomain=" + authorityDomain
                + "|resourceClass=" + resourceClass
                + "|contract=" + contractName
                + "|commandTopic=" + commandTopic
                + "|eventTopic=" + eventTopic
                + "|stateTopic=" + stateTopic
                + "|responseTopic=" + responseTopic
                + "|consumerGroup=" + consumerGroup
                + "|replayWatermark=" + replayWatermark
                + "|registrationEndpoint=" + registrationEndpoint.map(URI::toString).orElse("absent");
    }

    String storeBindingFingerprint() {
        return AuthorityBackendDescriptorDigests.sha256Hex(
                "kafka=" + kafkaBootstrapServers
                        + "\ncommandTopic=" + commandTopic
                        + "\neventTopic=" + eventTopic
                        + "\nstateTopic=" + stateTopic
                        + "\nresponseTopic=" + responseTopic
                        + "\nconsumerGroup=" + consumerGroup
                        + "\npostgresJdbcUrl=" + postgresJdbcUrl
                        + "\npostgresUsername=" + postgresUsername
                        + "\npostgresPasswordSet=" + !postgresPassword.isBlank()
                        + "\ncassandraContactPoints=" + cassandraContactPoints
                        + "\ncassandraLocalDatacenter=" + cassandraLocalDatacenter
                        + "\nvalkeyEndpoint=" + valkeyEndpoint);
    }

    private static long replayWatermark(Map<String, String> environment) {
        String raw = required(environment, "FULCRUM_ESCROW_REPLAY_WATERMARK");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("FULCRUM_ESCROW_REPLAY_WATERMARK must be a non-negative long", exception);
        }
    }

    private static String required(Map<String, String> environment, String name) {
        return requireNonBlank(environment.get(name), name);
    }

    private static Optional<URI> registrationEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        URI endpoint = URI.create(value.trim());
        if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("FULCRUM_REGISTRATION_ENDPOINT must use http or https");
        }
        return Optional.of(endpoint);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set");
        }
        return value;
    }

    private static String requireExpected(String value, String name, String expected) {
        String checked = requireNonBlank(value, name);
        if (!expected.equals(checked)) {
            throw new IllegalArgumentException(name + " must be " + expected + " but was " + checked);
        }
        return checked;
    }

    public enum StartupMode {
        SERVE,
        BOOTSTRAP_CHECK;

        static StartupMode from(String value) {
            String normalized = requireNonBlank(value, "startupMode")
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            return StartupMode.valueOf(normalized);
        }
    }
}
