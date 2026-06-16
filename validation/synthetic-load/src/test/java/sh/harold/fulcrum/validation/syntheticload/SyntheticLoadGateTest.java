package sh.harold.fulcrum.validation.syntheticload;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.adapters.agones.fake.FakeAgonesAllocationAdapter;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.PresenceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.control.route.AcknowledgeRouteAttempt;
import sh.harold.fulcrum.control.route.ControlRouteNames;
import sh.harold.fulcrum.control.route.IssueProxyRoute;
import sh.harold.fulcrum.control.route.ObserveHostAttach;
import sh.harold.fulcrum.control.route.PrepareHostRoute;
import sh.harold.fulcrum.control.route.RequestRouteAttempt;
import sh.harold.fulcrum.control.route.RouteAttemptCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlCommand;
import sh.harold.fulcrum.control.route.RouteAttemptControlRecord;
import sh.harold.fulcrum.control.route.RouteAttemptController;
import sh.harold.fulcrum.control.route.RouteAttemptDecision;
import sh.harold.fulcrum.control.route.RouteAttemptDecisionStatus;
import sh.harold.fulcrum.control.route.RouteAttemptId;
import sh.harold.fulcrum.control.route.RouteAttemptLifecycleStatus;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.InMemoryIdempotencyLedger;
import sh.harold.fulcrum.host.api.HostAllocationClaim;
import sh.harold.fulcrum.host.api.HostAllocationRequest;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostReadinessReport;
import sh.harold.fulcrum.standard.rank.EffectiveRankProjection;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankGranted;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SyntheticLoadGateTest {
    private static final Instant BASE_TIME = Instant.parse("2026-06-17T00:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("synthetic-load-client");
    private static final PoolId POOL_ID = new PoolId("pool-synthetic-paper");
    private static final ResolvedManifestId MANIFEST_ID = new ResolvedManifestId("manifest-synthetic-load");

    @Test
    void reportsSyntheticLatencyAgainstInitialScaleBudgets() {
        List<LatencyReport> reports = List.of(
                allocationLatencyReport(),
                routeLatencyReport(),
                authorityLatencyReport(),
                projectionRebuildLatencyReport());

        assertEquals(List.of("allocation", "route", "authority", "projection-rebuild"),
                reports.stream().map(LatencyReport::workload).toList());
        assertTrue(reports.stream().allMatch(LatencyReport::orderedPercentiles), () -> evidence(reports));
        assertTrue(reports.stream().allMatch(LatencyReport::withinBudget), () -> evidence(reports));
    }

    private static LatencyReport allocationLatencyReport() {
        int samples = 128;
        FakeAgonesAllocationAdapter adapter = new FakeAgonesAllocationAdapter();
        for (int index = 0; index < samples; index++) {
            adapter.registerReadyPaperInstance(readyReport(index));
        }
        return measure("allocation", samples, Duration.ofMillis(100), index -> {
            HostAllocationClaim claim = adapter.allocate(new HostAllocationRequest(
                    POOL_ID,
                    new SessionId("session-synthetic-" + index),
                    MANIFEST_ID,
                    trace("trace-allocation-" + index, "span-allocation"),
                    BASE_TIME.plusMillis(index)));
            if (!claim.sessionId().equals(new SessionId("session-synthetic-" + index))) {
                throw new AssertionError("allocation returned claim for wrong Session");
            }
        });
    }

    private static LatencyReport routeLatencyReport() {
        return measure("route", 128, Duration.ofMillis(100), index -> {
            RouteAttemptController controller = new RouteAttemptController();
            RouteAttemptControlRecord record = RouteAttemptController.emptyRecord(51);
            RouteAttemptId routeAttemptId = new RouteAttemptId("route-load-" + index);

            record = acceptedRoute(controller, record, requestRoute(routeAttemptId, index), ControlRouteNames.REQUEST_ROUTE_ATTEMPT, "request", index).record();
            record = acceptedRoute(controller, record, new IssueProxyRoute(routeAttemptId, BASE_TIME.plusMillis(index).plusMillis(1)),
                    ControlRouteNames.ISSUE_PROXY_ROUTE, "proxy", index).record();
            record = acceptedRoute(controller, record, new PrepareHostRoute(routeAttemptId, BASE_TIME.plusMillis(index).plusMillis(2)),
                    ControlRouteNames.PREPARE_HOST_ROUTE, "host", index).record();
            record = acceptedRoute(controller, record, new ObserveHostAttach(routeAttemptId, BASE_TIME.plusMillis(index).plusMillis(3)),
                    ControlRouteNames.OBSERVE_HOST_ATTACH, "attach", index).record();
            RouteAttemptDecision ack = acceptedRoute(controller, record, new AcknowledgeRouteAttempt(routeAttemptId, BASE_TIME.plusMillis(index).plusMillis(4)),
                    ControlRouteNames.ACKNOWLEDGE_ROUTE_ATTEMPT, "ack", index);

            if (ack.record().snapshot().orElseThrow().status() != RouteAttemptLifecycleStatus.ACKED) {
                throw new AssertionError("route workload did not reach ACKED");
            }
        });
    }

    private static LatencyReport authorityLatencyReport() {
        AuthorityWorkload workload = new AuthorityWorkload();
        return measure("authority", 512, Duration.ofMillis(100), workload::process);
    }

    private static LatencyReport projectionRebuildLatencyReport() {
        List<RankGranted> events = rankEvents(256, 8);
        return measure("projection-rebuild", 24, Duration.ofMillis(500), ignored -> {
            EffectiveRankProjection projection = EffectiveRankProjection.rebuild(events);
            if (projection.rows().size() != 256) {
                throw new AssertionError("projection rebuild row count mismatch");
            }
        });
    }

    private static LatencyReport measure(String workload, int samples, Duration p99Budget, IntConsumer operation) {
        long[] nanos = new long[samples];
        for (int index = 0; index < samples; index++) {
            long started = System.nanoTime();
            operation.accept(index);
            nanos[index] = System.nanoTime() - started;
        }
        Arrays.sort(nanos);
        return new LatencyReport(
                workload,
                samples,
                Duration.ofNanos(percentile(nanos, 50)),
                Duration.ofNanos(percentile(nanos, 95)),
                Duration.ofNanos(percentile(nanos, 99)),
                p99Budget);
    }

    private static long percentile(long[] sortedNanos, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(sortedNanos.length - 1, index))];
    }

    private static String evidence(List<LatencyReport> reports) {
        return reports.stream().map(LatencyReport::evidenceLine).collect(Collectors.joining(System.lineSeparator()));
    }

    private static HostReadinessReport readyReport(int index) {
        return new HostReadinessReport(
                new HostInstanceIdentity(
                        new InstanceId("instance-synthetic-paper-" + index),
                        HostInstanceKinds.PAPER,
                        POOL_ID,
                        new MachineRef("machine-synthetic-" + (index % 8)),
                        new PrincipalId("principal-synthetic-paper-" + index)),
                MANIFEST_ID,
                trace("trace-ready-" + index, "span-ready"),
                BASE_TIME.minusMillis(1));
    }

    private static RouteAttemptDecision acceptedRoute(
            RouteAttemptController controller,
            RouteAttemptControlRecord record,
            RouteAttemptCommand payload,
            CommandName commandName,
            String phase,
            int index) {
        RouteAttemptDecision decision = controller.handle(routeCommand(
                payload,
                commandName,
                "cmd-" + phase + "-" + index,
                "idem-" + phase + "-" + index,
                BASE_TIME.plusMillis(index),
                Optional.of(record.revision())),
                record);
        if (decision.status() != RouteAttemptDecisionStatus.ACCEPTED) {
            throw new AssertionError("route command rejected: " + decision.receipt());
        }
        return decision;
    }

    private static <T extends RouteAttemptCommand> RouteAttemptControlCommand<T> routeCommand(
            T payload,
            CommandName commandName,
            String commandId,
            String idempotencyKey,
            Instant receivedAt,
            Optional<Revision> expectedRevision) {
        return new RouteAttemptControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        ControlRouteNames.aggregateId(payload.routeAttemptId()),
                        ControlRouteNames.CONTRACT,
                        commandName,
                        trace("trace-route-" + commandId, "span-route"),
                        Optional.of(receivedAt.plusSeconds(30)),
                        payload),
                PRINCIPAL,
                51,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + commandId,
                receivedAt);
    }

    private static RequestRouteAttempt requestRoute(RouteAttemptId routeAttemptId, int index) {
        return new RequestRouteAttempt(
                routeAttemptId,
                new RouteId("route-synthetic-" + index),
                new SessionId("session-route-synthetic-" + index),
                new SlotId("slot-route-synthetic-" + index),
                List.of(new SubjectId(new UUID(1L, index + 1L))),
                List.of(new InstanceId("instance-velocity-synthetic-" + (index % 4))),
                new PresenceId("presence-synthetic-" + index),
                new InstanceId("instance-paper-synthetic-" + index),
                MANIFEST_ID,
                BASE_TIME.plusMillis(index),
                BASE_TIME.plusSeconds(30),
                trace("trace-route-request-" + index, "span-route-request"));
    }

    private static List<RankGranted> rankEvents(int subjectCount, int updatesPerSubject) {
        List<RankGranted> events = new ArrayList<>(subjectCount * updatesPerSubject);
        for (int subjectIndex = 0; subjectIndex < subjectCount; subjectIndex++) {
            SubjectId subjectId = new SubjectId(new UUID(2L, subjectIndex + 1L));
            for (int revision = 1; revision <= updatesPerSubject; revision++) {
                EffectiveRankSnapshot snapshot = new EffectiveRankSnapshot(
                        subjectId,
                        "rank-" + revision,
                        "rank:rank-" + revision,
                        PRINCIPAL,
                        BASE_TIME.plusSeconds(revision));
                events.add(new RankGranted(snapshot, new Revision(revision)));
            }
        }
        return List.copyOf(events);
    }

    private static AuthorityCommand<AddValue> authorityCommand(int index, Revision expectedRevision) {
        CommandEnvelope<AddValue> envelope = new CommandEnvelope<>(
                new CommandId("authority-command-" + index),
                new IdempotencyKey("authority-idem-" + index),
                PRINCIPAL,
                new AggregateId("authority-load-aggregate"),
                new ContractName("synthetic-authority"),
                new CommandName("synthetic.add-value"),
                trace("trace-authority-" + index, "span-authority"),
                Optional.empty(),
                new AddValue(1));
        return new AuthorityCommand<>(
                envelope,
                PRINCIPAL,
                61,
                Optional.of(expectedRevision),
                "payload-" + index,
                BASE_TIME.plusMillis(index));
    }

    private static TraceEnvelope trace(String traceId, String spanId) {
        return new TraceEnvelope(
                traceId,
                spanId,
                Optional.empty(),
                BASE_TIME,
                "synthetic-load-gate",
                new InstanceId("instance-synthetic-load"));
    }

    private record LatencyReport(
            String workload,
            int samples,
            Duration p50,
            Duration p95,
            Duration p99,
            Duration p99Budget) {
        private boolean orderedPercentiles() {
            return p50.compareTo(p95) <= 0 && p95.compareTo(p99) <= 0;
        }

        private boolean withinBudget() {
            return p99.compareTo(p99Budget) <= 0;
        }

        private String evidenceLine() {
            return "%s samples=%d p50=%s p95=%s p99=%s budget.p99=%s"
                    .formatted(workload, samples, p50, p95, p99, p99Budget);
        }
    }

    private static final class AuthorityWorkload {
        private final AtomicInteger mutationRuns = new AtomicInteger();
        private final AuthorityCommandProcessor<ValueState, AddValue, ValueReceipt> processor;
        private AuthorityRecord<ValueState> record = new AuthorityRecord<>(new Revision(0), 61, new ValueState(0));

        private AuthorityWorkload() {
            processor = new AuthorityCommandProcessor<>(
                    new InMemoryIdempotencyLedger<>(),
                    reason -> new ValueReceipt(false, 0, new Revision(0)),
                    (command, current) -> {
                        mutationRuns.incrementAndGet();
                        Revision revision = new Revision(current.revision().value() + 1);
                        ValueState state = new ValueState(current.state().value() + command.envelope().payload().amount());
                        return new AuthorityMutationResult<>(
                                revision,
                                state,
                                new ValueReceipt(true, state.value(), revision),
                                List.of(
                                        new AuthorityEmission(AuthorityEmissionKind.EVENT, command.envelope().aggregateId().value(), state.wireValue(revision)),
                                        new AuthorityEmission(AuthorityEmissionKind.STATE, command.envelope().aggregateId().value(), state.wireValue(revision)),
                                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, command.envelope().aggregateId().value(), state.wireValue(revision)),
                                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, command.envelope().aggregateId().value(), state.wireValue(revision))));
                    });
        }

        private void process(int index) {
            AuthorityDecision<ValueState, ValueReceipt> decision = processor.process(authorityCommand(index, record.revision()), record);
            if (decision.status() != AuthorityDecisionStatus.ACCEPTED || decision.replayed()) {
                throw new AssertionError("authority workload command was not accepted");
            }
            record = new AuthorityRecord<>(decision.revision(), record.fencingEpoch(), decision.state());
        }
    }

    private record AddValue(int amount) implements CommandPayload {
    }

    private record ValueState(int value) {
        private String wireValue(Revision revision) {
            return "value=" + value + "\nrevision=" + revision.value();
        }
    }

    private record ValueReceipt(boolean accepted, int value, Revision revision) {
    }
}
