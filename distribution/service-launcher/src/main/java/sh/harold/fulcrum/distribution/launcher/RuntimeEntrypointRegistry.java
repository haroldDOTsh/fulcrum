package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

final class RuntimeEntrypointRegistry {
    private static final String MAIN_CLASS = "sh.harold.fulcrum.distribution.launcher.FulcrumLauncher";
    private static final RuntimeBindingRequirement KAFKA = requirement(
            "FULCRUM_KAFKA_BOOTSTRAP_SERVERS",
            "Kafka command, event, state, and response log clients");
    private static final RuntimeBindingRequirement POSTGRES_URL = requirement(
            "FULCRUM_POSTGRES_JDBC_URL",
            "PostgreSQL authority record adapter");
    private static final RuntimeBindingRequirement POSTGRES_USER = requirement(
            "FULCRUM_POSTGRES_USERNAME",
            "PostgreSQL authority credential user");
    private static final RuntimeBindingRequirement POSTGRES_PASSWORD = requirement(
            "FULCRUM_POSTGRES_PASSWORD",
            "PostgreSQL authority credential secret");
    private static final RuntimeBindingRequirement CASSANDRA = requirement(
            "FULCRUM_CASSANDRA_CONTACT_POINTS",
            "Cassandra projection writer adapter");
    private static final RuntimeBindingRequirement VALKEY = requirement(
            "FULCRUM_VALKEY_ENDPOINT",
            "Valkey idempotency and cache adapter");
    private static final RuntimeBindingRequirement AGONES = requirement(
            "FULCRUM_AGONES_ALLOCATOR_URL",
            "Agones allocation adapter");
    private static final RuntimeBindingRequirement AGONES_NAMESPACE = requirement(
            "FULCRUM_AGONES_NAMESPACE",
            "Agones GameServer namespace");
    private static final RuntimeBindingRequirement OBJECT_STORE = new RuntimeBindingRequirement(
            "FULCRUM_OBJECT_STORE_ROOT|FULCRUM_OBJECT_STORE_ENDPOINT",
            "object storage artifact source",
            RuntimeEntrypointRegistry::hasObjectStoreBinding);
    private static final RuntimeBindingRequirement PAPER_ROOT = requirement(
            "FULCRUM_PAPER_SERVER_ROOT",
            "Paper server bootstrap");
    private static final RuntimeBindingRequirement VELOCITY_ROOT = requirement(
            "FULCRUM_VELOCITY_SERVER_ROOT",
            "Velocity proxy bootstrap");

    private static final List<LaunchEntry> ENTRIES = List.of(
            new LaunchEntry(
                    LaunchRole.AUTHORITY_SERVICE,
                    "authority",
                    MAIN_CLASS,
                    List.of(KAFKA, POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD, CASSANDRA, VALKEY)
            ),
            new LaunchEntry(
                    LaunchRole.CONTROLLER_SERVICE,
                    "control",
                    MAIN_CLASS,
                    List.of(
                            requirement("FULCRUM_CONTROL_KAFKA_BOOTSTRAP_SERVERS", "Kafka control command, event, and state log clients"),
                            AGONES,
                            AGONES_NAMESPACE,
                            requirement("FULCRUM_CONTROL_STATE_TOPIC", "control-plane authority runtime binding"),
                            requirement("FULCRUM_HOST_COMMAND_TOPIC", "Paper host command channel"),
                            requirement("FULCRUM_HOST_OBSERVATION_TOPIC", "Paper host observation channel"),
                            requirement("FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC", "Velocity addressed proxy route command channel")
                    )
            ),
            new LaunchEntry(
                    LaunchRole.WORKER_AGENT,
                    "host-worker",
                    MAIN_CLASS,
                    List.of(
                            requirement("FULCRUM_WORKER_KAFKA_BOOTSTRAP_SERVERS", "Kafka worker job and result log clients"),
                            requirement("FULCRUM_WORKER_JOB_TOPIC", "worker job command source"),
                            requirement("FULCRUM_WORKER_RESULT_TOPIC", "worker result emission sink"),
                            requirement("FULCRUM_WORKER_OBJECT_BUCKET", "worker object storage result bucket"),
                            OBJECT_STORE
                    )
            ),
            new LaunchEntry(
                    LaunchRole.PAPER_AGENT,
                    "host-paper",
                    MAIN_CLASS,
                    List.of(
                            PAPER_ROOT,
                            OBJECT_STORE,
                            requirement("FULCRUM_PAPER_KAFKA_BOOTSTRAP_SERVERS", "Kafka Paper Session command and host observation producers"),
                            requirement("FULCRUM_PAPER_AGONES_SDK_URL", "Agones GameServer SDK sidecar endpoint"),
                            requirement("FULCRUM_PAPER_OBSERVATION_BRIDGE_URL", "local Paper plugin observation bridge"),
                            requirement("FULCRUM_PAPER_CAPABILITY_BRIDGE_URL", "local Paper plugin capability bridge"),
                            requirement("FULCRUM_PAPER_REWARD_BRIDGE_URL", "local Paper plugin reward bridge"),
                            VALKEY,
                            requirement("FULCRUM_PAPER_EXPERIENCE_ID", "Paper Experience assignment"),
                            requirement("FULCRUM_PAPER_SESSION_ID", "Paper Session assignment"),
                            requirement("FULCRUM_PAPER_SLOT_ID", "Paper Slot assignment"),
                            requirement("FULCRUM_PAPER_RESOLVED_MANIFEST_ID", "Paper ResolvedManifest assignment"),
                            requirement("FULCRUM_PAPER_CODE_ARTIFACT_ID", "Paper code artifact pin"),
                            requirement("FULCRUM_PAPER_WORLD_ARTIFACT_ID", "Paper world content artifact pin"),
                            requirement("FULCRUM_PAPER_WORLD_ARTIFACT_DIGEST", "Paper world content artifact digest"),
                            requirement("FULCRUM_PAPER_WORLD_ARTIFACT_COMPATIBILITY", "Paper world content artifact compatibility"),
                            requirement("FULCRUM_PAPER_SESSION_OWNER_TOKEN", "Paper Session owner token"),
                            requirement("FULCRUM_PAPER_SESSION_LEASE", "Paper Session lease duration"),
                            requirement("FULCRUM_PAPER_HOST_RUNTIME_ABI", "Paper host runtime ABI"),
                            requirement("FULCRUM_HOST_COMMAND_TOPIC", "host command channel"),
                            requirement("FULCRUM_HOST_OBSERVATION_TOPIC", "host observation channel")
                    )
            ),
            new LaunchEntry(
                    LaunchRole.VELOCITY_AGENT,
                    "host-velocity",
                    MAIN_CLASS,
                    List.of(
                            VELOCITY_ROOT,
                            requirement("FULCRUM_VELOCITY_KAFKA_BOOTSTRAP_SERVERS", "Velocity route command Kafka client"),
                            requirement("FULCRUM_VELOCITY_ROUTE_BRIDGE_URL", "local Velocity plugin route execution bridge"),
                            requirement("FULCRUM_VELOCITY_LOGIN_GATE_BRIDGE_URL", "local Velocity login gate decision bridge"),
                            requirement("FULCRUM_VELOCITY_ROUTE_COMMAND_TOPIC", "Velocity addressed proxy route command channel"),
                            requirement("FULCRUM_ROUTE_COMMAND_TOPIC", "route command and observation channel"),
                            requirement("FULCRUM_QUEUE_ROSTER_COMMAND_TOPIC", "queue-roster command channel"),
                            requirement("FULCRUM_PRESENCE_COMMAND_TOPIC", "Presence authority command channel"),
                            requirement("FULCRUM_SHARED_SHARD_PLACEMENT_COMMAND_TOPIC", "shared-shard placement command channel"),
                            requirement("FULCRUM_ROUTE_ATTEMPT_COMMAND_TOPIC", "route-attempt control command channel"),
                            requirement("FULCRUM_LIFECYCLE_TRACE_COMMAND_TOPIC", "lifecycle trace control command channel"),
                            requirement("FULCRUM_SHARED_SHARD_ALLOCATION_STATE_TOPIC", "shared-shard allocation endpoint state topic"),
                            requirement("FULCRUM_LOGIN_GATE_SCOPE", "login gate contribution binding"),
                            VALKEY
                    )
            )
    );

    private RuntimeEntrypointRegistry() {
    }

    static LaunchPlan plan(LaunchCommand command, ClassLoader classLoader) {
        ProfileDescriptor descriptor = command.profile().loadDescriptor(classLoader);
        return new LaunchPlan(descriptor, command.mode(), entriesFor(command.role()));
    }

    static List<LaunchEntry> entriesFor(LaunchRole role) {
        if (role == LaunchRole.ALL) {
            return ENTRIES;
        }
        return ENTRIES.stream()
                .filter(entry -> entry.role() == role)
                .toList();
    }

    private static RuntimeBindingRequirement requirement(String name, String description) {
        return new RuntimeBindingRequirement(name, description);
    }

    private static boolean hasObjectStoreBinding(RuntimeEnvironment environment) {
        String mode = environment.value("FULCRUM_OBJECT_STORE_MODE")
                .orElse("local")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
        if ("local".equals(mode)) {
            return environment.contains("FULCRUM_OBJECT_STORE_ROOT");
        }
        if ("s3".equals(mode)) {
            return environment.contains("FULCRUM_OBJECT_STORE_ENDPOINT")
                    && environment.contains("FULCRUM_OBJECT_STORE_ACCESS_KEY")
                    && environment.contains("FULCRUM_OBJECT_STORE_SECRET_KEY");
        }
        return false;
    }
}
