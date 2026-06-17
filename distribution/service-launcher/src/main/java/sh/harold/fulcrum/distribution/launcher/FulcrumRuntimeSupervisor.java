package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class FulcrumRuntimeSupervisor implements AutoCloseable {
    private final LaunchPlan plan;
    private final RuntimeEnvironment environment;
    private final String probeHost;
    private final int requestedProbePort;
    private final RuntimeExternalClients externalClients;
    private final List<ManagedRuntimeService> services;
    private RuntimeProbeServer probeServer;

    FulcrumRuntimeSupervisor(LaunchPlan plan, RuntimeEnvironment environment, String probeHost, int requestedProbePort) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.probeHost = Objects.requireNonNull(probeHost, "probeHost");
        this.requestedProbePort = requestedProbePort;
        RuntimeConnectionSettings connectionSettings = RuntimeConnectionSettings.resolve(plan, environment);
        this.externalClients = RuntimeExternalClients.create(connectionSettings);
        this.services = plan.entries().stream()
                .map(entry -> new ManagedRuntimeService(
                        entry,
                        RuntimeIdentityIssuer.issue(plan.profile(), entry, environment),
                        connectionSettings,
                        externalClients))
                .toList();
    }

    void start() {
        if (!plan.canStart(environment)) {
            throw new IllegalStateException("Cannot start Fulcrum runtime with missing bindings");
        }
        services.forEach(ManagedRuntimeService::start);
        try {
            probeServer = new RuntimeProbeServer(probeHost, requestedProbePort, this::snapshots);
            probeServer.start();
        } catch (IOException exception) {
            close();
            throw new IllegalStateException("Could not start runtime probe server", exception);
        }
    }

    void await(Optional<Duration> runFor) {
        if (runFor.isPresent()) {
            sleep(runFor.orElseThrow());
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            sleep(Duration.ofSeconds(1));
        }
    }

    int probePort() {
        if (probeServer == null) {
            throw new IllegalStateException("probe server has not started");
        }
        return probeServer.port();
    }

    String displayHost() {
        if ("0.0.0.0".equals(probeHost)) {
            return "127.0.0.1";
        }
        return probeHost;
    }

    List<RuntimeServiceSnapshot> snapshots() {
        return services.stream()
                .map(ManagedRuntimeService::snapshot)
                .toList();
    }

    @Override
    public void close() {
        if (probeServer != null) {
            probeServer.close();
        }
        services.forEach(ManagedRuntimeService::close);
        externalClients.close();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
