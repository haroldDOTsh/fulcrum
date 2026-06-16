package sh.harold.fulcrum.host.worker;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record WorkerJobRequest(
        WorkerJobId jobId,
        WorkerJobKind jobKind,
        String workKey,
        IdempotencyKey idempotencyKey,
        String payloadFingerprint,
        ResolvedManifestId resolvedManifestId,
        TraceEnvelope traceEnvelope,
        Instant enqueuedAt,
        Optional<Instant> deadlineAt) {
    public WorkerJobRequest {
        jobId = Objects.requireNonNull(jobId, "jobId");
        jobKind = Objects.requireNonNull(jobKind, "jobKind");
        workKey = WorkerNames.requireNonBlank(workKey, "workKey");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        payloadFingerprint = WorkerNames.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        traceEnvelope = Objects.requireNonNull(traceEnvelope, "traceEnvelope");
        enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        deadlineAt = deadlineAt == null ? Optional.empty() : deadlineAt;
    }
}
