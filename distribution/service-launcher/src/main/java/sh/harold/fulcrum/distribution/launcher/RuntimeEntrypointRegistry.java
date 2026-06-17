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
    private static final RuntimeBindingRequirement OBJECT_STORE = requirement(
            "FULCRUM_OBJECT_STORE_ROOT",
            "object storage artifact source");
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
                            requirement("FULCRUM_CONTROL_STATE_TOPIC", "control-plane authority runtime binding")
                    )
            ),
            new LaunchEntry(
                    LaunchRole.WORKER_AGENT,
                    "host-worker",
                    MAIN_CLASS,
                    List.of(
                            requirement("FULCRUM_WORKER_JOB_TOPIC", "worker job command source"),
                            requirement("FULCRUM_WORKER_RESULT_TOPIC", "worker result emission sink"),
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
                            requirement("FULCRUM_HOST_COMMAND_TOPIC", "host command and observation channel")
                    )
            ),
            new LaunchEntry(
                    LaunchRole.VELOCITY_AGENT,
                    "host-velocity",
                    MAIN_CLASS,
                    List.of(
                            VELOCITY_ROOT,
                            requirement("FULCRUM_ROUTE_COMMAND_TOPIC", "route command and observation channel"),
                            requirement("FULCRUM_LOGIN_GATE_SCOPE", "login gate contribution binding")
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
}
