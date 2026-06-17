package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.velocity.VelocityLoginGateBridgeServer;
import sh.harold.fulcrum.host.worker.WorkerAgentRuntime;
import sh.harold.fulcrum.host.worker.WorkerJobKind;
import sh.harold.fulcrum.host.worker.WorkerLagBudget;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class RuntimeServiceEngines {
    private RuntimeServiceEngines() {
    }

    static RuntimeServiceEngine create(
            LaunchEntry entry,
            HostSecurityContext securityContext,
            RuntimeConnectionSettings connectionSettings,
            RuntimeExternalClients externalClients) {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(connectionSettings, "connectionSettings");
        Objects.requireNonNull(externalClients, "externalClients");
        if (entry.role() == LaunchRole.AUTHORITY_SERVICE) {
            connectionSettings.authority().orElseThrow();
            RuntimeExternalClients.AuthorityClients authorityClients =
                    externalClients.authority().orElseThrow();
            ExternalAuthorityRuntimeBindings bindings = new ExternalAuthorityRuntimeBindings(authorityClients);
            return new AuthorityRuntimeServiceEngine(
                    new AuthorityWorkerCatalog(bindings, 1).workerBindings(),
                    Duration.ofMillis(50));
        }
        if (entry.role() == LaunchRole.CONTROLLER_SERVICE) {
            connectionSettings.controller().orElseThrow();
            RuntimeExternalClients.ControllerClients controllerClients =
                    externalClients.controller().orElseThrow();
            LocalControllerRuntimeBindings bindings = new LocalControllerRuntimeBindings();
            List<ControllerWorkerBinding> workers = new ArrayList<>();
            workers.add(new ControllerWorkerBinding(
                    ExternalInstanceRegistryControllerWorker.DOMAIN,
                    new ExternalInstanceRegistryControllerWorker(controllerClients, 1)));
            workers.addAll(new ExternalControllerWorkerCatalog(controllerClients, 1).workerBindings());
            workers.add(new ControllerWorkerBinding(
                    ExternalHostObservationRouteWorker.DOMAIN,
                    new ExternalHostObservationRouteWorker(controllerClients, securityContext)));
            workers.addAll(new ControllerWorkerCatalog(
                    bindings,
                    controllerClients.allocationPort(),
                    1).workerBindings());
            return new ControllerRuntimeServiceEngine(
                    workers,
                    Duration.ofMillis(50));
        }
        if (entry.role() == LaunchRole.WORKER_AGENT) {
            connectionSettings.worker().orElseThrow();
            RuntimeExternalClients.WorkerClients workerClients =
                    externalClients.worker().orElseThrow();
            LocalWorkerRuntimeBindings bindings = new LocalWorkerRuntimeBindings();
            WorkerAgentRuntime runtime = new WorkerAgentRuntime(
                    securityContext,
                    new ResolvedManifestId("manifest-" + entry.processFamily()),
                    Arrays.stream(WorkerJobKind.values())
                            .map(kind -> new WorkerLagBudget(kind, Duration.ofSeconds(30)))
                            .toList());
            List<WorkerJobBinding> workers = new ArrayList<>();
            WorkerJobObjectHandler handler = new WorkerJobObjectHandler(workerClients.objectStorage());
            workers.add(new WorkerJobBinding(
                    ExternalWorkerJobWorker.DOMAIN,
                    new ExternalWorkerJobWorker(workerClients, runtime, handler, Clock.systemUTC())));
            workers.addAll(new WorkerJobCatalog(
                    runtime,
                    bindings,
                    workerClients.objectStorage(),
                    Clock.systemUTC()).workerBindings());
            return new WorkerRuntimeServiceEngine(
                    workers,
                    Duration.ofMillis(50));
        }
        if (entry.role() == LaunchRole.PAPER_AGENT) {
            connectionSettings.paper().orElseThrow();
            RuntimeExternalClients.PaperClients paperClients = externalClients.paper().orElseThrow();
            return PaperRuntimeServiceEngine.create(securityContext, paperClients);
        }
        if (entry.role() == LaunchRole.VELOCITY_AGENT) {
            connectionSettings.velocity().orElseThrow();
            RuntimeExternalClients.VelocityClients velocityClients = externalClients.velocity().orElseThrow();
            VelocitySharedShardAllocationRegistry allocations = new VelocitySharedShardAllocationRegistry();
            ValkeyPunishmentLoginGateEvaluator punishmentGate = new ValkeyPunishmentLoginGateEvaluator(
                    securityContext,
                    velocityClients.valkey(),
                    velocityClients.settings().loginGateScope());
            return new VelocityRuntimeServiceEngine(
                    new ExternalVelocityRouteWorker(velocityClients, securityContext, allocations),
                    new VelocityLoginGateBridgeServer(
                            velocityClients.settings().loginGateBridgeUrl(),
                            new VelocityLoginRoutingEvaluator(
                                    punishmentGate,
                                    velocityClients.velocityKafka().producer(),
                                    securityContext,
                                    velocityClients.settings(),
                                    allocations)),
                    Duration.ofMillis(50));
        }
        return new HeartbeatRuntimeServiceEngine("fulcrum-" + entry.role().id());
    }
}
