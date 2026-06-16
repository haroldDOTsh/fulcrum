package sh.harold.fulcrum.distribution.launcher;

import java.util.List;

final class RuntimeEntrypointRegistry {
    private static final String MAIN_CLASS = "sh.harold.fulcrum.distribution.launcher.FulcrumLauncher";

    private static final List<LaunchEntry> ENTRIES = List.of(
            new LaunchEntry(
                    LaunchRole.AUTHORITY_SERVICE,
                    "authority",
                    MAIN_CLASS,
                    List.of(
                            "Kafka command, event, state, and response log clients",
                            "PostgreSQL authority record adapter",
                            "Cassandra projection writer adapter",
                            "Valkey idempotency and cache adapter"
                    )
            ),
            new LaunchEntry(
                    LaunchRole.CONTROLLER_SERVICE,
                    "control",
                    MAIN_CLASS,
                    List.of(
                            "Kafka control command, event, and state log clients",
                            "Agones allocation adapter",
                            "control-plane authority runtime binding"
                    )
            ),
            new LaunchEntry(
                    LaunchRole.WORKER_AGENT,
                    "host-worker",
                    MAIN_CLASS,
                    List.of(
                            "worker job command source",
                            "worker result emission sink",
                            "object storage artifact source"
                    )
            ),
            new LaunchEntry(
                    LaunchRole.PAPER_AGENT,
                    "host-paper",
                    MAIN_CLASS,
                    List.of(
                            "Paper server bootstrap",
                            "artifact source binding",
                            "host command and observation channel"
                    )
            ),
            new LaunchEntry(
                    LaunchRole.VELOCITY_AGENT,
                    "host-velocity",
                    MAIN_CLASS,
                    List.of(
                            "Velocity proxy bootstrap",
                            "route command and observation channel",
                            "login gate contribution binding"
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
}
