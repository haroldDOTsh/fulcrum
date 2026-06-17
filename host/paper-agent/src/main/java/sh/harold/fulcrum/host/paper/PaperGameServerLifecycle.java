package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostReadinessReport;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class PaperGameServerLifecycle {
    private static final String AGONES_ALLOCATED_STATE = "Allocated";
    private static final long INITIAL_SESSION_OWNER_EPOCH = 1;

    private final HostSecurityContext securityContext;
    private final AgonesGameServerSdkClient agones;
    private final PaperArtifactCache artifactCache;
    private final PaperWorldInstaller worldInstaller;
    private final PaperSessionLifecyclePort sessionLifecyclePort;
    private final PaperObservationSink observationSink;
    private final Clock clock;

    public PaperGameServerLifecycle(
            HostSecurityContext securityContext,
            AgonesGameServerSdkClient agones,
            PaperArtifactCache artifactCache,
            PaperWorldInstaller worldInstaller,
            PaperSessionLifecyclePort sessionLifecyclePort,
            PaperObservationSink observationSink,
            Clock clock) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.agones = Objects.requireNonNull(agones, "agones");
        this.artifactCache = Objects.requireNonNull(artifactCache, "artifactCache");
        this.worldInstaller = Objects.requireNonNull(worldInstaller, "worldInstaller");
        this.sessionLifecyclePort = Objects.requireNonNull(sessionLifecyclePort, "sessionLifecyclePort");
        this.observationSink = Objects.requireNonNull(observationSink, "observationSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!HostInstanceKinds.PAPER.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("PaperGameServerLifecycle requires a Paper Instance identity");
        }
    }

    public PaperPreparedWorld prepareWorldAndReportReady(
            PaperGameServerAssignment assignment,
            TraceEnvelope traceEnvelope) throws IOException {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        CachedArtifact artifact = artifactCache.pullVerified(assignment.worldArtifact());
        PaperPreparedWorld preparedWorld = worldInstaller.install(artifact, assignment);
        agones.ready();
        agones.health();
        observationSink.publish(HostObservationFactory.readiness(new HostReadinessReport(
                securityContext.identity(),
                assignment.resolvedManifestId(),
                traceEnvelope,
                clock.instant())));
        return preparedWorld;
    }

    public Optional<PaperGameServerAssignment> activateSessionIfAllocated(
            PaperGameServerAssignment assignment,
            TraceEnvelope traceEnvelope) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        AgonesGameServerSnapshot snapshot = agones.gameServer();
        if (!AGONES_ALLOCATED_STATE.equals(snapshot.state())) {
            return Optional.empty();
        }
        return Optional.of(openAllocatedSession(assignment.withAllocationMetadata(snapshot), traceEnvelope));
    }

    public PaperGameServerAssignment activateAllocatedSession(
            PaperGameServerAssignment assignment,
            TraceEnvelope traceEnvelope) {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        AgonesGameServerSnapshot snapshot = agones.gameServer();
        if (!AGONES_ALLOCATED_STATE.equals(snapshot.state())) {
            throw new IllegalStateException("Agones GameServer must be Allocated before opening a Session");
        }
        return openAllocatedSession(assignment.withAllocationMetadata(snapshot), traceEnvelope);
    }

    private PaperGameServerAssignment openAllocatedSession(
            PaperGameServerAssignment allocatedAssignment,
            TraceEnvelope traceEnvelope) {
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plus(allocatedAssignment.sessionLease());
        sessionLifecyclePort.openSession(new PaperSessionOpenRequest(
                allocatedAssignment.sessionId(),
                allocatedAssignment.experienceId(),
                allocatedAssignment.slotId(),
                securityContext.identity().instanceId(),
                allocatedAssignment.sessionOwnerToken(),
                allocatedAssignment.resolvedManifestId(),
                now,
                leaseExpiresAt,
                traceEnvelope));
        sessionLifecyclePort.activateSession(new PaperSessionActivationRequest(
                allocatedAssignment.sessionId(),
                allocatedAssignment.sessionOwnerToken(),
                INITIAL_SESSION_OWNER_EPOCH,
                now,
                leaseExpiresAt,
                traceEnvelope));
        return allocatedAssignment;
    }

    public void reportHealth() {
        agones.health();
    }

    public void shutdown() {
        agones.shutdown();
    }
}
