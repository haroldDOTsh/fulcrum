package sh.harold.fulcrum.host.worker;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record WorkerJobReceipt(
        WorkerJobDecisionStatus status,
        boolean accepted,
        WorkerJobId jobId,
        WorkerJobKind jobKind,
        String workKey,
        IdempotencyKey idempotencyKey,
        ResolvedManifestId resolvedManifestId,
        InstanceId workerInstanceId,
        Duration observedLag,
        Optional<WorkerJobResult> result,
        Optional<WorkerJobRejectionReason> rejectionReason,
        TraceEnvelope traceEnvelope) {
    public WorkerJobReceipt {
        status = Objects.requireNonNull(status, "status");
        jobId = Objects.requireNonNull(jobId, "jobId");
        jobKind = Objects.requireNonNull(jobKind, "jobKind");
        workKey = WorkerNames.requireNonBlank(workKey, "workKey");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        workerInstanceId = Objects.requireNonNull(workerInstanceId, "workerInstanceId");
        observedLag = Objects.requireNonNull(observedLag, "observedLag");
        if (observedLag.isNegative()) {
            throw new IllegalArgumentException("observedLag must be non-negative");
        }
        result = result == null ? Optional.empty() : result;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        if (accepted && result.isEmpty()) {
            throw new IllegalArgumentException("accepted worker job requires a result");
        }
        if (!accepted && rejectionReason.isEmpty()) {
            throw new IllegalArgumentException("rejected worker job requires a rejection reason");
        }
    }

    public static WorkerJobReceipt accepted(
            WorkerJobRequest request,
            InstanceId workerInstanceId,
            Duration observedLag,
            WorkerJobResult result) {
        return new WorkerJobReceipt(
                WorkerJobDecisionStatus.ACCEPTED,
                true,
                request.jobId(),
                request.jobKind(),
                request.workKey(),
                request.idempotencyKey(),
                request.resolvedManifestId(),
                workerInstanceId,
                observedLag,
                Optional.of(result),
                Optional.empty(),
                request.traceEnvelope());
    }

    public static WorkerJobReceipt rejected(
            WorkerJobRequest request,
            InstanceId workerInstanceId,
            Duration observedLag,
            WorkerJobRejectionReason reason) {
        return new WorkerJobReceipt(
                WorkerJobDecisionStatus.REJECTED,
                false,
                request.jobId(),
                request.jobKind(),
                request.workKey(),
                request.idempotencyKey(),
                request.resolvedManifestId(),
                workerInstanceId,
                observedLag,
                Optional.empty(),
                Optional.of(reason),
                request.traceEnvelope());
    }

    public WorkerJobReceipt asReplay() {
        return new WorkerJobReceipt(
                WorkerJobDecisionStatus.REPLAYED,
                accepted,
                jobId,
                jobKind,
                workKey,
                idempotencyKey,
                resolvedManifestId,
                workerInstanceId,
                observedLag,
                result,
                rejectionReason,
                traceEnvelope);
    }
}
