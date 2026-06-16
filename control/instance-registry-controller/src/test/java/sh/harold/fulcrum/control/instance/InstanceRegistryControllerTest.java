package sh.harold.fulcrum.control.instance;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InstanceRegistryControllerTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("control-instance");
    private static final InstanceId INSTANCE = new InstanceId("paper-1");
    private static final PoolId POOL = new PoolId("duel-paper");
    private static final MachineRef MACHINE = new MachineRef("machine-a");
    private static final ResolvedManifestId MANIFEST = new ResolvedManifestId("manifest-duel-v1");

    @Test
    void registersReadyDrainingAndOfflineInstanceWithReplayableEvents() {
        InstanceRegistryController controller = new InstanceRegistryController();
        InstanceRegistryRecord record = InstanceRegistryController.emptyRecord(5);

        InstanceRegistryDecision registered = controller.handle(
                controlCommand("cmd-1", "idem-1", ControlInstanceNames.REGISTER, register(), Optional.of(new Revision(0)), PRINCIPAL, 5),
                record);
        InstanceRegistryDecision ready = controller.handle(
                controlCommand("cmd-2", "idem-2", ControlInstanceNames.MARK_READY, ready(), Optional.of(new Revision(1)), PRINCIPAL, 5),
                registered.record());
        InstanceRegistryDecision draining = controller.handle(
                controlCommand("cmd-3", "idem-3", ControlInstanceNames.MARK_DRAINING, draining(), Optional.of(new Revision(2)), PRINCIPAL, 5),
                ready.record());
        InstanceRegistryDecision offline = controller.handle(
                controlCommand("cmd-4", "idem-4", ControlInstanceNames.MARK_OFFLINE, offline(), Optional.of(new Revision(3)), PRINCIPAL, 5),
                draining.record());

        assertEquals(InstanceRegistryDecisionStatus.ACCEPTED, registered.status());
        assertEquals(InstanceRegistryStatus.READY, ready.record().snapshot().orElseThrow().status());
        assertEquals(Optional.of(MANIFEST), ready.record().snapshot().orElseThrow().resolvedManifestId());
        assertEquals(InstanceRegistryStatus.DRAINING, draining.record().snapshot().orElseThrow().status());
        assertEquals(InstanceRegistryStatus.OFFLINE, offline.record().snapshot().orElseThrow().status());
        assertEquals(new Revision(4), offline.revision());
        assertTrue(ready.emissions().stream().anyMatch(emission -> emission.kind() == InstanceRegistryEmissionKind.READY_INSTANCE));
        assertTrue(draining.emissions().stream().anyMatch(emission -> emission.kind() == InstanceRegistryEmissionKind.DRAINING_INSTANCE));
        assertTrue(offline.emissions().stream().anyMatch(emission -> emission.kind() == InstanceRegistryEmissionKind.OFFLINE_INSTANCE));

        InstanceRegistryRecord replayed = InstanceRegistryController.replay(
                5,
                List.of(
                        registered.events().getFirst(),
                        ready.events().getFirst(),
                        draining.events().getFirst(),
                        offline.events().getFirst()));
        assertEquals(offline.record(), replayed);
    }

    @Test
    void duplicateIdempotencyReplaysWithoutEmissions() {
        InstanceRegistryController controller = new InstanceRegistryController();
        InstanceRegistryRecord record = InstanceRegistryController.emptyRecord(5);
        InstanceRegistryControlCommand<RegisterInstance> command =
                controlCommand("cmd-5", "idem-5", ControlInstanceNames.REGISTER, register(), Optional.of(new Revision(0)), PRINCIPAL, 5);

        InstanceRegistryDecision accepted = controller.handle(command, record);
        InstanceRegistryDecision replayed = controller.handle(command, accepted.record());

        assertEquals(InstanceRegistryDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(InstanceRegistryDecisionStatus.REPLAYED, replayed.status());
        assertEquals(accepted.receipt(), replayed.receipt());
        assertTrue(replayed.events().isEmpty());
        assertTrue(replayed.emissions().isEmpty());
    }

    @Test
    void staleOwnerCannotReplayStoredDecision() {
        InstanceRegistryController controller = new InstanceRegistryController();
        InstanceRegistryControlCommand<RegisterInstance> command =
                controlCommand("cmd-6", "idem-6", ControlInstanceNames.REGISTER, register(), Optional.of(new Revision(0)), PRINCIPAL, 5);
        InstanceRegistryDecision accepted = controller.handle(command, InstanceRegistryController.emptyRecord(5));

        InstanceRegistryDecision stale = controller.handle(
                controlCommand("cmd-6", "idem-6", ControlInstanceNames.REGISTER, register(), Optional.of(new Revision(0)), PRINCIPAL, 4),
                accepted.record());

        assertEquals(InstanceRegistryDecisionStatus.REJECTED, stale.status());
        assertEquals(Optional.of(InstanceRegistryRejectionReason.STALE_FENCING_EPOCH), stale.receipt().rejectionReason());
        assertFalse(stale.receipt().accepted());
    }

    @Test
    void offlineInstanceRejectsFurtherReadyMutation() {
        InstanceRegistryController controller = new InstanceRegistryController();
        InstanceRegistryDecision registered = controller.handle(
                controlCommand("cmd-7", "idem-7", ControlInstanceNames.REGISTER, register(), Optional.of(new Revision(0)), PRINCIPAL, 5),
                InstanceRegistryController.emptyRecord(5));
        InstanceRegistryDecision offline = controller.handle(
                controlCommand("cmd-8", "idem-8", ControlInstanceNames.MARK_OFFLINE, offline(), Optional.of(new Revision(1)), PRINCIPAL, 5),
                registered.record());

        InstanceRegistryDecision rejected = controller.handle(
                controlCommand("cmd-9", "idem-9", ControlInstanceNames.MARK_READY, ready(), Optional.of(new Revision(2)), PRINCIPAL, 5),
                offline.record());

        assertEquals(InstanceRegistryDecisionStatus.REJECTED, rejected.status());
        assertEquals(Optional.of(InstanceRegistryRejectionReason.INSTANCE_OFFLINE), rejected.receipt().rejectionReason());
    }

    private static RegisterInstance register() {
        return new RegisterInstance(INSTANCE, "paper", POOL, MACHINE, PRINCIPAL, NOW, trace());
    }

    private static MarkInstanceReady ready() {
        return new MarkInstanceReady(INSTANCE, MANIFEST, NOW.plusSeconds(5), trace());
    }

    private static MarkInstanceDraining draining() {
        return new MarkInstanceDraining(INSTANCE, "rebalance", NOW.plusSeconds(10), trace());
    }

    private static MarkInstanceOffline offline() {
        return new MarkInstanceOffline(INSTANCE, "terminated", NOW.plusSeconds(15), trace());
    }

    private static <T extends InstanceRegistryCommand> InstanceRegistryControlCommand<T> controlCommand(
            String commandId,
            String idempotencyKey,
            CommandName commandName,
            T payload,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch) {
        return new InstanceRegistryControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        aggregateId(payload),
                        ControlInstanceNames.CONTRACT,
                        commandName,
                        trace(),
                        Optional.empty(),
                        payload),
                authenticatedPrincipal,
                fencingEpoch,
                expectedRevision,
                payload.getClass().getSimpleName() + ":" + idempotencyKey,
                NOW);
    }

    private static AggregateId aggregateId(InstanceRegistryCommand payload) {
        return ControlInstanceNames.aggregateId(payload.instanceId());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-instance",
                "span-instance",
                Optional.empty(),
                NOW,
                "instance-registry-test",
                INSTANCE);
    }
}
