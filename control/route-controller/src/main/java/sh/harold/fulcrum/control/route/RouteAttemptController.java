package sh.harold.fulcrum.control.route;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.Revision;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RouteAttemptController {
    private final Map<IdempotencyKey, StoredRouteAttemptDecision> idempotencyLedger = new HashMap<>();

    public RouteAttemptDecision handle(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(currentRecord, "currentRecord");

        Optional<RouteAttemptRejectionReason> trustBoundaryRejection = trustBoundaryRejection(command, currentRecord);
        if (trustBoundaryRejection.isPresent()) {
            return RouteAttemptDecision.rejected(
                    trustBoundaryRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        StoredRouteAttemptDecision stored = idempotencyLedger.get(command.envelope().idempotencyKey());
        if (stored != null) {
            if (stored.payloadFingerprint().equals(command.payloadFingerprint())) {
                return stored.decision().asReplay();
            }
            return RouteAttemptDecision.rejected(
                    RouteAttemptRejectionReason.IDEMPOTENCY_CONFLICT,
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
        }

        Optional<RouteAttemptRejectionReason> commandRejection = commandRejection(command, currentRecord);
        if (commandRejection.isPresent()) {
            RouteAttemptDecision decision = RouteAttemptDecision.rejected(
                    commandRejection.orElseThrow(),
                    currentRecord.revision(),
                    currentRecord,
                    command.envelope().traceEnvelope(),
                    command.fencingEpoch(),
                    command.envelope().idempotencyKey().value(),
                    command.envelope().commandId().value());
            idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredRouteAttemptDecision(command.payloadFingerprint(), decision));
            return decision;
        }

        RouteAttemptDecision decision = accepted(command, currentRecord);
        idempotencyLedger.put(command.envelope().idempotencyKey(), new StoredRouteAttemptDecision(command.payloadFingerprint(), decision));
        return decision;
    }

    public static RouteAttemptControlRecord replay(long fencingEpoch, List<RouteAttemptEvent> events) {
        Objects.requireNonNull(events, "events");
        RouteAttemptControlRecord record = RouteAttemptControlRecord.empty(fencingEpoch);
        for (RouteAttemptEvent event : events) {
            record = record.withSnapshot(event.revision(), event.snapshot());
        }
        return record;
    }

    public static RouteAttemptControlRecord emptyRecord(long fencingEpoch) {
        return RouteAttemptControlRecord.empty(fencingEpoch);
    }

    private static Optional<RouteAttemptRejectionReason> trustBoundaryRejection(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        if (!command.envelope().principalId().equals(command.authenticatedPrincipal())) {
            return Optional.of(RouteAttemptRejectionReason.PRINCIPAL_MISMATCH);
        }
        if (command.fencingEpoch() != currentRecord.fencingEpoch()) {
            return Optional.of(RouteAttemptRejectionReason.STALE_FENCING_EPOCH);
        }
        return Optional.empty();
    }

    private static Optional<RouteAttemptRejectionReason> commandRejection(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        boolean expired = command.envelope().deadlineAt()
                .map(deadline -> !deadline.isAfter(command.receivedAt()))
                .orElse(false);
        if (expired) {
            return Optional.of(RouteAttemptRejectionReason.DEADLINE_EXPIRED);
        }
        if (command.expectedRevision().isPresent() && !command.expectedRevision().orElseThrow().equals(currentRecord.revision())) {
            return Optional.of(RouteAttemptRejectionReason.REVISION_MISMATCH);
        }
        if (!command.envelope().aggregateId().equals(ControlRouteNames.aggregateId(command.envelope().payload().routeAttemptId()))) {
            return Optional.of(RouteAttemptRejectionReason.AGGREGATE_MISMATCH);
        }
        if (!command.envelope().contractName().equals(ControlRouteNames.CONTRACT)) {
            return Optional.of(RouteAttemptRejectionReason.CONTRACT_MISMATCH);
        }
        return transitionRejection(command, currentRecord);
    }

    private static Optional<RouteAttemptRejectionReason> transitionRejection(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        RouteAttemptCommand payload = command.envelope().payload();
        Optional<RouteAttemptSnapshot> current = currentRecord.snapshot();
        if (payload instanceof RequestRouteAttempt) {
            return current.isPresent()
                    ? Optional.of(RouteAttemptRejectionReason.ROUTE_ATTEMPT_ALREADY_EXISTS)
                    : Optional.empty();
        }
        if (current.isEmpty()) {
            return Optional.of(RouteAttemptRejectionReason.ROUTE_ATTEMPT_NOT_OPENED);
        }
        RouteAttemptLifecycleStatus status = current.orElseThrow().status();
        if (payload instanceof IssueProxyRoute) {
            return status == RouteAttemptLifecycleStatus.CREATED
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
        }
        if (payload instanceof PrepareHostRoute) {
            return status == RouteAttemptLifecycleStatus.ISSUED_TO_PROXY
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
        }
        if (payload instanceof ObserveHostAttach) {
            return status == RouteAttemptLifecycleStatus.ISSUED_TO_HOST
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
        }
        if (payload instanceof AcknowledgeRouteAttempt) {
            return status == RouteAttemptLifecycleStatus.HOST_ATTACH_OBSERVED
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
        }
        if (payload instanceof TimeoutRouteAttempt) {
            if (isTerminal(status)) {
                return Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
            }
            return !current.orElseThrow().deadlineAt().isAfter(command.receivedAt())
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.DEADLINE_NOT_REACHED);
        }
        if (payload instanceof RetryRouteAttempt) {
            return status == RouteAttemptLifecycleStatus.TIMED_OUT || status == RouteAttemptLifecycleStatus.FAILED
                    ? Optional.empty()
                    : Optional.of(RouteAttemptRejectionReason.INVALID_TRANSITION);
        }
        return Optional.of(RouteAttemptRejectionReason.UNKNOWN_COMMAND);
    }

    private static boolean isTerminal(RouteAttemptLifecycleStatus status) {
        return status == RouteAttemptLifecycleStatus.ACKED
                || status == RouteAttemptLifecycleStatus.TIMED_OUT
                || status == RouteAttemptLifecycleStatus.FAILED
                || status == RouteAttemptLifecycleStatus.CANCELLED;
    }

    private static RouteAttemptDecision accepted(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        RouteAttemptSnapshot snapshot = nextSnapshot(command, currentRecord);
        RouteAttemptControlRecord nextRecord = currentRecord.withSnapshot(nextRevision, snapshot);
        RouteAttemptEvent event = RouteAttemptEvent.from(command, nextRevision, snapshot);
        RouteAttemptReceipt receipt = RouteAttemptReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        return RouteAttemptDecision.accepted(
                nextRevision,
                nextRecord,
                receipt,
                List.of(event),
                emissions(command, snapshot, event, receipt));
    }

    private static RouteAttemptSnapshot nextSnapshot(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptControlRecord currentRecord) {
        RouteAttemptCommand payload = command.envelope().payload();
        if (payload instanceof RequestRouteAttempt request) {
            return RouteAttemptSnapshot.from(request);
        }
        RouteAttemptSnapshot current = currentRecord.snapshot().orElseThrow();
        if (payload instanceof IssueProxyRoute issueProxy) {
            return current.transition(RouteAttemptLifecycleStatus.ISSUED_TO_PROXY, Optional.empty(), current.retryCount(), issueProxy.issuedAt(), current.deadlineAt());
        }
        if (payload instanceof PrepareHostRoute prepareHost) {
            return current.transition(RouteAttemptLifecycleStatus.ISSUED_TO_HOST, Optional.empty(), current.retryCount(), prepareHost.issuedAt(), current.deadlineAt());
        }
        if (payload instanceof ObserveHostAttach observeHostAttach) {
            return current.transition(RouteAttemptLifecycleStatus.HOST_ATTACH_OBSERVED, Optional.empty(), current.retryCount(), observeHostAttach.observedAt(), current.deadlineAt());
        }
        if (payload instanceof AcknowledgeRouteAttempt acknowledge) {
            return current.transition(RouteAttemptLifecycleStatus.ACKED, Optional.empty(), current.retryCount(), acknowledge.acknowledgedAt(), current.deadlineAt());
        }
        if (payload instanceof TimeoutRouteAttempt timeout) {
            return current.transition(RouteAttemptLifecycleStatus.TIMED_OUT, Optional.of("deadline-expired"), current.retryCount(), timeout.timedOutAt(), current.deadlineAt());
        }
        if (payload instanceof RetryRouteAttempt retry) {
            if (!retry.newDeadlineAt().isAfter(retry.retryAt())) {
                throw new IllegalArgumentException("newDeadlineAt must be after retryAt");
            }
            return current.transition(RouteAttemptLifecycleStatus.CREATED, Optional.empty(), current.retryCount() + 1, retry.retryAt(), retry.newDeadlineAt());
        }
        throw new IllegalArgumentException("unknown RouteAttempt command");
    }

    private static List<RouteAttemptControlEmission> emissions(
            RouteAttemptControlCommand<? extends RouteAttemptCommand> command,
            RouteAttemptSnapshot snapshot,
            RouteAttemptEvent event,
            RouteAttemptReceipt receipt) {
        String stateValue = snapshot.wireValue(event.revision());
        RouteAttemptCommand payload = command.envelope().payload();
        List<RouteAttemptControlEmission> base = new java.util.ArrayList<>(List.of(
                new RouteAttemptControlEmission(RouteAttemptControlEmissionKind.EVENT, event.eventKey(), event.wireValue()),
                new RouteAttemptControlEmission(RouteAttemptControlEmissionKind.STATE, ControlRouteNames.stateKey(snapshot.routeAttemptId()), stateValue),
                new RouteAttemptControlEmission(RouteAttemptControlEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue())));
        if (payload instanceof IssueProxyRoute) {
            base.add(new RouteAttemptControlEmission(RouteAttemptControlEmissionKind.PROXY_COMMAND, snapshot.routeAttemptId().value(), snapshot.proxyCommandValue()));
        }
        if (payload instanceof PrepareHostRoute) {
            base.add(new RouteAttemptControlEmission(RouteAttemptControlEmissionKind.HOST_COMMAND, snapshot.routeAttemptId().value(), snapshot.hostCommandValue()));
        }
        return List.copyOf(base);
    }
}

record StoredRouteAttemptDecision(String payloadFingerprint, RouteAttemptDecision decision) {
    StoredRouteAttemptDecision {
        payloadFingerprint = ControlRouteStrings.requireNonBlank(payloadFingerprint, "payloadFingerprint");
        decision = Objects.requireNonNull(decision, "decision");
    }
}
