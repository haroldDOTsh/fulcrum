package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.core.artifact.ArtifactBlobLayout;
import sh.harold.fulcrum.core.artifact.ArtifactObjectAddress;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.paper.AgonesGameServerHttpClient;
import sh.harold.fulcrum.host.paper.ArtifactSource;
import sh.harold.fulcrum.host.paper.NoopPaperCapabilityBridge;
import sh.harold.fulcrum.host.paper.PaperAllocatedAssignmentFile;
import sh.harold.fulcrum.host.paper.PaperArtifactCache;
import sh.harold.fulcrum.host.paper.PaperCapabilityBridgeServer;
import sh.harold.fulcrum.host.paper.PaperGameServerAssignment;
import sh.harold.fulcrum.host.paper.PaperGameServerLifecycle;
import sh.harold.fulcrum.host.paper.PaperWorldArchiveInstaller;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class PaperRuntimeServiceEngine implements RuntimeServiceEngine {
    private final HostSecurityContext securityContext;
    private final PaperGameServerLifecycle lifecycle;
    private final PaperGameServerAssignment assignment;
    private final Path allocatedAssignmentFile;
    private final PaperObservationBridgeServer observationBridge;
    private final PaperCapabilityBridgeServer capabilityBridge;
    private final PaperRewardBridgeServer rewardBridge;
    private final Duration healthInterval;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean prepared = new AtomicBoolean(false);
    private final AtomicBoolean shutdownSent = new AtomicBoolean(false);
    private final AtomicLong loopCount = new AtomicLong();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private Thread thread;

    PaperRuntimeServiceEngine(
            HostSecurityContext securityContext,
            PaperGameServerLifecycle lifecycle,
            PaperGameServerAssignment assignment,
            Duration healthInterval,
            Clock clock) {
        this(securityContext, lifecycle, assignment, null, null, null, null, healthInterval, clock);
    }

    PaperRuntimeServiceEngine(
            HostSecurityContext securityContext,
            PaperGameServerLifecycle lifecycle,
            PaperGameServerAssignment assignment,
            Path allocatedAssignmentFile,
            Duration healthInterval,
            Clock clock) {
        this(securityContext, lifecycle, assignment, allocatedAssignmentFile, null, null, null, healthInterval, clock);
    }

    private PaperRuntimeServiceEngine(
            HostSecurityContext securityContext,
            PaperGameServerLifecycle lifecycle,
            PaperGameServerAssignment assignment,
            Path allocatedAssignmentFile,
            PaperObservationBridgeServer observationBridge,
            PaperCapabilityBridgeServer capabilityBridge,
            PaperRewardBridgeServer rewardBridge,
            Duration healthInterval,
            Clock clock) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.assignment = Objects.requireNonNull(assignment, "assignment");
        this.allocatedAssignmentFile = allocatedAssignmentFile;
        this.observationBridge = observationBridge;
        this.capabilityBridge = capabilityBridge;
        this.rewardBridge = rewardBridge;
        this.healthInterval = Objects.requireNonNull(healthInterval, "healthInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (healthInterval.isNegative() || healthInterval.isZero()) {
            throw new IllegalArgumentException("healthInterval must be positive");
        }
    }

    static PaperRuntimeServiceEngine create(
            HostSecurityContext securityContext,
            RuntimeExternalClients.PaperClients clients) {
        Objects.requireNonNull(securityContext, "securityContext");
        Objects.requireNonNull(clients, "clients");
        RuntimeConnectionSettings.PaperConnections settings = clients.settings();
        PaperGameServerAssignment assignment = new PaperGameServerAssignment(
                settings.experienceId(),
                settings.sessionId(),
                settings.slotId(),
                settings.resolvedManifest(),
                settings.worldArtifact(),
                settings.sessionOwnerToken(),
                settings.sessionLease());
        ArtifactSource artifactSource = artifactId -> {
            if (!assignment.worldArtifact().artifactId().equals(artifactId)) {
                throw new IOException("Unexpected Paper artifact request " + artifactId.value());
            }
            ArtifactObjectAddress address = ArtifactBlobLayout.objectAddress(
                    clients.objectBucket(),
                    assignment.worldArtifact());
            return clients.objectStorage().read(address)
                    .orElseThrow(() -> new IOException("Missing Paper world artifact " + artifactId.value()));
        };
        KafkaPaperObservationSink observationSink = new KafkaPaperObservationSink(
                securityContext,
                clients.paperKafka().producer(),
                settings.hostObservationTopic());
        PaperGameServerLifecycle lifecycle = new PaperGameServerLifecycle(
                securityContext,
                new AgonesGameServerHttpClient(settings.agonesSdkUrl()),
                new PaperArtifactCache(settings.paperServerRoot().resolve("artifact-cache"), artifactSource),
                new PaperWorldArchiveInstaller(settings.paperServerRoot().resolve("world")),
                new KafkaPaperSessionLifecyclePort(securityContext, clients.paperKafka().producer()),
                observationSink,
                Clock.systemUTC());
        return new PaperRuntimeServiceEngine(
                securityContext,
                lifecycle,
                assignment,
                settings.allocatedAssignmentFile(),
                new PaperObservationBridgeServer(settings.observationBridgeUrl(), observationSink),
                new PaperCapabilityBridgeServer(
                        settings.capabilityBridgeUrl(),
                        new NoopPaperCapabilityBridge()),
                new PaperRewardBridgeServer(
                        settings.rewardBridgeUrl(),
                        report -> {
                        },
                        settings.rewardDeliveryCopies()),
                Duration.ofSeconds(1),
                Clock.systemUTC());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("paper-agent is already running");
        }
        if (observationBridge != null) {
            try {
                observationBridge.start();
            } catch (RuntimeException exception) {
                running.set(false);
                throw exception;
            }
        }
        if (capabilityBridge != null) {
            try {
                capabilityBridge.start();
            } catch (RuntimeException exception) {
                if (observationBridge != null) {
                    observationBridge.close();
                }
                running.set(false);
                throw exception;
            }
        }
        if (rewardBridge != null) {
            try {
                rewardBridge.start();
            } catch (RuntimeException exception) {
                if (capabilityBridge != null) {
                    capabilityBridge.close();
                }
                if (observationBridge != null) {
                    observationBridge.close();
                }
                running.set(false);
                throw exception;
            }
        }
        thread = new Thread(this::runLoop, "fulcrum-paper-runtime");
        thread.setDaemon(false);
        thread.start();
    }

    @Override
    public boolean live() {
        Thread current = thread;
        return running.get() && current != null && current.isAlive();
    }

    @Override
    public boolean ready() {
        return ready.get();
    }

    @Override
    public long loopCount() {
        return loopCount.get();
    }

    @Override
    public void close() {
        running.set(false);
        ready.set(false);
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (prepared.get() && shutdownSent.compareAndSet(false, true)) {
            lifecycle.shutdown();
        }
        if (observationBridge != null) {
            observationBridge.close();
        }
        if (capabilityBridge != null) {
            capabilityBridge.close();
        }
        if (rewardBridge != null) {
            rewardBridge.close();
        }
    }

    Throwable failure() {
        Throwable runtimeFailure = failure.get();
        if (runtimeFailure != null) {
            return runtimeFailure;
        }
        Throwable observationFailure = observationBridge == null ? null : observationBridge.failure();
        if (observationFailure != null) {
            return observationFailure;
        }
        return rewardBridge == null ? null : rewardBridge.failure();
    }

    private void runLoop() {
        try {
            TraceEnvelope traceEnvelope = traceEnvelope();
            lifecycle.prepareWorldAndReportReady(assignment, traceEnvelope);
            prepared.set(true);
            PaperGameServerAssignment allocatedAssignment = waitForAllocation(traceEnvelope);
            if (allocatedAssignmentFile != null) {
                PaperAllocatedAssignmentFile.write(allocatedAssignmentFile, allocatedAssignment, traceEnvelope.traceId());
            }
            ready.set(true);
            while (running.get()) {
                sleep(healthInterval);
                if (Thread.currentThread().isInterrupted()) {
                    running.set(false);
                    break;
                }
                if (running.get()) {
                    lifecycle.reportHealth();
                    loopCount.incrementAndGet();
                }
            }
        } catch (IOException | RuntimeException exception) {
            failure.compareAndSet(null, exception);
            running.set(false);
        } finally {
            ready.set(false);
            running.set(false);
        }
    }

    private PaperGameServerAssignment waitForAllocation(TraceEnvelope traceEnvelope) {
        while (running.get()) {
            Optional<PaperGameServerAssignment> allocated =
                    lifecycle.activateSessionIfAllocated(assignment, traceEnvelope);
            if (allocated.isPresent()) {
                return allocated.orElseThrow();
            }
            lifecycle.reportHealth();
            loopCount.incrementAndGet();
            sleep(healthInterval);
            if (Thread.currentThread().isInterrupted()) {
                running.set(false);
                break;
            }
        }
        throw new IllegalStateException("Paper runtime stopped before Agones allocated the GameServer");
    }

    private TraceEnvelope traceEnvelope() {
        return new TraceEnvelope(
                "trace-paper-" + assignment.sessionId().value(),
                "span-paper-gameserver",
                Optional.empty(),
                clock.instant(),
                "paper-agent",
                securityContext.identity().instanceId());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
