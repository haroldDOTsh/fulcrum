package sh.harold.fulcrum.host.worker;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostReadinessReport;
import sh.harold.fulcrum.host.api.HostSecurityContext;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class WorkerAgentRuntime {
    private final HostSecurityContext securityContext;
    private final ResolvedManifestId resolvedManifestId;
    private final Map<WorkerJobKind, WorkerLagBudget> lagBudgets;
    private final Map<IdempotencyKey, StoredWorkerJobReceipt> idempotencyLedger = new HashMap<>();

    public WorkerAgentRuntime(
            HostSecurityContext securityContext,
            ResolvedManifestId resolvedManifestId,
            List<WorkerLagBudget> lagBudgets) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        if (!HostInstanceKinds.WORKER.equals(securityContext.identity().instanceKind())) {
            throw new IllegalArgumentException("WorkerAgentRuntime requires a worker Instance identity");
        }
        this.lagBudgets = Objects.requireNonNull(lagBudgets, "lagBudgets").stream()
                .collect(Collectors.toUnmodifiableMap(WorkerLagBudget::jobKind, budget -> budget, (left, right) -> {
                    throw new IllegalArgumentException("duplicate worker lag budget for " + left.jobKind());
                }));
    }

    public HostReadinessReport readiness(TraceEnvelope traceEnvelope, Instant readyAt) {
        return new HostReadinessReport(
                securityContext.identity(),
                resolvedManifestId,
                Objects.requireNonNull(traceEnvelope, "traceEnvelope"),
                Objects.requireNonNull(readyAt, "readyAt"));
    }

    public WorkerJobReceipt handle(
            WorkerJobRequest request,
            WorkerJobHandler handler,
            Instant startedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(startedAt, "startedAt");

        Duration observedLag = observedLag(request, startedAt);
        StoredWorkerJobReceipt stored = idempotencyLedger.get(request.idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(request.payloadFingerprint())) {
                return stored.receipt().asReplay();
            }
            return WorkerJobReceipt.rejected(
                    request,
                    securityContext.identity().instanceId(),
                    observedLag,
                    WorkerJobRejectionReason.IDEMPOTENCY_CONFLICT);
        }

        Optional<WorkerJobRejectionReason> rejection = rejection(request, observedLag, startedAt);
        if (rejection.isPresent()) {
            WorkerJobReceipt receipt = WorkerJobReceipt.rejected(
                    request,
                    securityContext.identity().instanceId(),
                    observedLag,
                    rejection.orElseThrow());
            idempotencyLedger.put(request.idempotencyKey(), new StoredWorkerJobReceipt(request.payloadFingerprint(), receipt));
            return receipt;
        }

        WorkerJobResult result = handler.handle(request);
        WorkerJobReceipt receipt = WorkerJobReceipt.accepted(
                request,
                securityContext.identity().instanceId(),
                observedLag,
                result);
        idempotencyLedger.put(request.idempotencyKey(), new StoredWorkerJobReceipt(request.payloadFingerprint(), receipt));
        return receipt;
    }

    private Optional<WorkerJobRejectionReason> rejection(
            WorkerJobRequest request,
            Duration observedLag,
            Instant startedAt) {
        boolean expired = request.deadlineAt()
                .map(deadline -> !deadline.isAfter(startedAt))
                .orElse(false);
        if (expired) {
            return Optional.of(WorkerJobRejectionReason.DEADLINE_EXPIRED);
        }
        WorkerLagBudget budget = lagBudgets.get(request.jobKind());
        if (budget != null && observedLag.compareTo(budget.maxLag()) > 0) {
            return Optional.of(WorkerJobRejectionReason.LAG_BUDGET_EXCEEDED);
        }
        return Optional.empty();
    }

    private static Duration observedLag(WorkerJobRequest request, Instant startedAt) {
        Duration lag = Duration.between(request.enqueuedAt(), startedAt);
        return lag.isNegative() ? Duration.ZERO : lag;
    }
}

record StoredWorkerJobReceipt(String payloadFingerprint, WorkerJobReceipt receipt) {
    StoredWorkerJobReceipt {
        payloadFingerprint = WorkerNames.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        receipt = Objects.requireNonNull(receipt, "receipt");
    }
}
