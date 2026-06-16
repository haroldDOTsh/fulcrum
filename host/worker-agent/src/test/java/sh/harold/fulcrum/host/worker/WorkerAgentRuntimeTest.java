package sh.harold.fulcrum.host.worker;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostObservationTypes;
import sh.harold.fulcrum.host.api.HostReadinessReport;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkerAgentRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final ResolvedManifestId MANIFEST = new ResolvedManifestId("worker-manifest-v1");
    private static final HostSecurityContext WORKER_CONTEXT = new HostSecurityContext(
            new HostInstanceIdentity(
                    new InstanceId("worker-1"),
                    HostInstanceKinds.WORKER,
                    new PoolId("worker-content"),
                    new MachineRef("machine-worker-a"),
                    new PrincipalId("principal-worker-1")),
            "worker-credential",
            HostCredentialScope.of(
                    new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.CONSUME, "worker.content-validation"),
                    new HostResourceGrant(HostResourceFamily.ARTIFACT, HostAccessMode.READ, "artifact.content")));

    @Test
    void readinessReportUsesWorkerIdentityAndManifest() {
        WorkerAgentRuntime runtime = new WorkerAgentRuntime(
                WORKER_CONTEXT,
                MANIFEST,
                List.of(new WorkerLagBudget(WorkerJobKind.CONTENT_VALIDATION, Duration.ofSeconds(30))));

        HostReadinessReport readiness = runtime.readiness(trace(), NOW);
        HostObservation observation = HostObservationFactory.readiness(readiness);

        assertEquals(WORKER_CONTEXT.identity(), readiness.instanceIdentity());
        assertEquals(MANIFEST, readiness.resolvedManifestId());
        assertEquals(HostObservationTypes.READINESS, observation.observationType());
        assertEquals("worker", observation.attributes().get("instanceKind"));
        assertEquals("worker-content", observation.attributes().get("poolId"));
    }

    @Test
    void rejectsNonWorkerIdentityAtRuntimeCreation() {
        HostSecurityContext paperContext = new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("paper-1"),
                        HostInstanceKinds.PAPER,
                        new PoolId("paper-small"),
                        new MachineRef("machine-paper-a"),
                        new PrincipalId("principal-paper-1")),
                "paper-credential",
                HostCredentialScope.of());

        assertThrows(IllegalArgumentException.class, () -> new WorkerAgentRuntime(paperContext, MANIFEST, List.of()));
    }

    @Test
    void acceptedJobRunsHandlerOnceAndReportsLag() {
        AtomicInteger runs = new AtomicInteger();
        WorkerAgentRuntime runtime = newRuntime(Duration.ofSeconds(30));
        WorkerJobRequest request = request("job-1", "idem-1", NOW, Optional.empty(), "fingerprint-1");

        WorkerJobReceipt receipt = runtime.handle(request, handledBy(runs), NOW.plusSeconds(5));

        assertEquals(WorkerJobDecisionStatus.ACCEPTED, receipt.status());
        assertTrue(receipt.accepted());
        assertEquals(Duration.ofSeconds(5), receipt.observedLag());
        assertEquals(MANIFEST, receipt.resolvedManifestId());
        assertEquals("ok", receipt.result().orElseThrow().resultCode());
        assertEquals(1, runs.get());
    }

    @Test
    void duplicateIdempotencyReplaysWithoutRerunningHandler() {
        AtomicInteger runs = new AtomicInteger();
        WorkerAgentRuntime runtime = newRuntime(Duration.ofSeconds(30));
        WorkerJobRequest request = request("job-2", "idem-2", NOW, Optional.empty(), "fingerprint-2");

        WorkerJobReceipt accepted = runtime.handle(request, handledBy(runs), NOW.plusSeconds(5));
        WorkerJobReceipt replayed = runtime.handle(request, handledBy(runs), NOW.plusSeconds(7));

        assertEquals(WorkerJobDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(WorkerJobDecisionStatus.REPLAYED, replayed.status());
        assertEquals(accepted.result(), replayed.result());
        assertEquals(Duration.ofSeconds(5), replayed.observedLag());
        assertEquals(1, runs.get());
    }

    @Test
    void lagBudgetRejectionDoesNotRunHandler() {
        AtomicInteger runs = new AtomicInteger();
        WorkerAgentRuntime runtime = newRuntime(Duration.ofSeconds(3));
        WorkerJobRequest request = request("job-3", "idem-3", NOW, Optional.empty(), "fingerprint-3");

        WorkerJobReceipt receipt = runtime.handle(request, handledBy(runs), NOW.plusSeconds(4));

        assertEquals(WorkerJobDecisionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(WorkerJobRejectionReason.LAG_BUDGET_EXCEEDED), receipt.rejectionReason());
        assertEquals(0, runs.get());
    }

    @Test
    void idempotencyConflictDoesNotRunHandler() {
        AtomicInteger runs = new AtomicInteger();
        WorkerAgentRuntime runtime = newRuntime(Duration.ofSeconds(30));
        WorkerJobRequest original = request("job-4", "idem-4", NOW, Optional.empty(), "fingerprint-4");
        WorkerJobRequest conflicting = request("job-5", "idem-4", NOW, Optional.empty(), "fingerprint-conflict");

        runtime.handle(original, handledBy(runs), NOW.plusSeconds(1));
        WorkerJobReceipt conflict = runtime.handle(conflicting, handledBy(runs), NOW.plusSeconds(2));

        assertEquals(WorkerJobDecisionStatus.REJECTED, conflict.status());
        assertEquals(Optional.of(WorkerJobRejectionReason.IDEMPOTENCY_CONFLICT), conflict.rejectionReason());
        assertEquals(1, runs.get());
    }

    private static WorkerAgentRuntime newRuntime(Duration maxLag) {
        return new WorkerAgentRuntime(
                WORKER_CONTEXT,
                MANIFEST,
                List.of(new WorkerLagBudget(WorkerJobKind.CONTENT_VALIDATION, maxLag)));
    }

    private static WorkerJobHandler handledBy(AtomicInteger runs) {
        return request -> {
            runs.incrementAndGet();
            return new WorkerJobResult("ok", "worker-output:" + request.workKey());
        };
    }

    private static WorkerJobRequest request(
            String jobId,
            String idempotencyKey,
            Instant enqueuedAt,
            Optional<Instant> deadlineAt,
            String payloadFingerprint) {
        return new WorkerJobRequest(
                new WorkerJobId(jobId),
                WorkerJobKind.CONTENT_VALIDATION,
                "artifact:sha-256:abc",
                new IdempotencyKey(idempotencyKey),
                payloadFingerprint,
                MANIFEST,
                trace(),
                enqueuedAt,
                deadlineAt);
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-worker",
                "span-worker",
                Optional.empty(),
                NOW,
                "worker-agent-test",
                WORKER_CONTEXT.identity().instanceId());
    }
}
