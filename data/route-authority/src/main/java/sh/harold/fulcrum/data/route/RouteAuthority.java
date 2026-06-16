package sh.harold.fulcrum.data.route;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;

import java.util.List;
import java.util.Objects;

public final class RouteAuthority {
    private static final String CONTRACT_NAME = "route";

    private final AuthorityCommandProcessor<RouteState, RouteCommand, RouteReceipt> processor;

    public RouteAuthority(IdempotencyLedger<RouteState, RouteReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                RouteAuthority::rejectionReceipt,
                this::apply);
    }

    public AuthorityDecision<RouteState, RouteReceipt> handle(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<RouteState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, RouteState.empty());
    }

    public static AggregateId aggregateId(RouteId routeId) {
        Objects.requireNonNull(routeId, "routeId");
        return new AggregateId("route:" + routeId.value());
    }

    public static String cacheKey(RouteId routeId) {
        return CONTRACT_NAME + ":" + aggregateId(routeId).value();
    }

    private AuthorityMutationResult<RouteState, RouteReceipt> apply(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord) {
        RouteCommand payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.routeId()))) {
            throw new IllegalArgumentException("route aggregate must be keyed by Route");
        }

        if (payload instanceof OpenRoute open) {
            return open(command, currentRecord, open);
        }
        if (payload instanceof AcknowledgeRoute acknowledge) {
            return acknowledge(command, currentRecord, acknowledge);
        }
        if (payload instanceof TimeoutRoute timeout) {
            return timeout(command, currentRecord, timeout);
        }
        throw new IllegalArgumentException("unknown Route command");
    }

    private AuthorityMutationResult<RouteState, RouteReceipt> open(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord,
            OpenRoute payload) {
        if (!payload.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalArgumentException("route must expire after authority receipt");
        }
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalStateException("Route is immutable once opened except lifecycle closure");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, RouteChangeKind.OPENED, RouteSnapshot.from(payload));
    }

    private AuthorityMutationResult<RouteState, RouteReceipt> acknowledge(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord,
            AcknowledgeRoute payload) {
        RouteSnapshot current = pendingCurrent(command, currentRecord);
        requireSameTarget(current, payload);
        if (!current.expiresAt().isAfter(command.receivedAt())) {
            throw new IllegalStateException("expired Route cannot be acknowledged");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, RouteChangeKind.ACKNOWLEDGED, current.acknowledge(payload));
    }

    private AuthorityMutationResult<RouteState, RouteReceipt> timeout(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord,
            TimeoutRoute payload) {
        RouteSnapshot current = pendingCurrent(command, currentRecord);

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        return accepted(command, nextRevision, RouteChangeKind.TIMED_OUT, current.timeout(payload));
    }

    private AuthorityMutationResult<RouteState, RouteReceipt> accepted(
            AuthorityCommand<RouteCommand> command,
            Revision revision,
            RouteChangeKind changeKind,
            RouteSnapshot snapshot) {
        RouteState state = new RouteState(snapshot);
        RouteReceipt receipt = RouteReceipt.accepted(
                snapshot,
                revision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        String aggregateKey = aggregateId(snapshot.routeId()).value();
        return new AuthorityMutationResult<>(
                revision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, RouteChanged.from(changeKind, snapshot, revision).wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, state.wireValue(revision)),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.routeId()), state.wireValue(revision))));
    }

    private static RouteSnapshot pendingCurrent(
            AuthorityCommand<RouteCommand> command,
            AuthorityRecord<RouteState> currentRecord) {
        RouteSnapshot current = currentRecord.state().current()
                .orElseThrow(() -> new IllegalStateException("Route must be opened before lifecycle commands"));
        if (!current.routeId().equals(command.envelope().payload().routeId())) {
            throw new IllegalArgumentException("route command id must match current aggregate");
        }
        if (current.status() != RouteLifecycleStatus.PENDING) {
            throw new IllegalStateException("closed Route cannot be mutated");
        }
        return current;
    }

    private static void requireSameTarget(RouteSnapshot current, AcknowledgeRoute payload) {
        if (!current.subjectId().equals(payload.subjectId())
                || !current.targetSessionId().equals(payload.targetSessionId())
                || !current.targetInstanceId().equals(payload.targetInstanceId())) {
            throw new IllegalArgumentException("route acknowledgement must match opened target");
        }
    }

    private static RouteReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return RouteReceipt.rejected(reason.name());
    }
}
