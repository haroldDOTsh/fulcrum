package sh.harold.fulcrum.control.capability;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.ExperienceId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.capability.api.CapabilityScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapabilityEnablementControllerTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("control-capability");
    private static final CapabilityScope SCOPE = CapabilityScope.experience(new ExperienceId("duel"));
    private static final CapabilityId RANK = new CapabilityId("rank");

    @Test
    void enablesDisablesAndReplaysScopedCapabilityState() {
        CapabilityEnablementController controller = new CapabilityEnablementController();
        CapabilityEnablementControlRecord record = CapabilityEnablementController.emptyRecord(SCOPE, 6);

        CapabilityEnablementDecision enabled = controller.handle(
                controlCommand("cmd-1", "idem-1", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(0)), PRINCIPAL, 6),
                record);
        CapabilityEnablementDecision disabled = controller.handle(
                controlCommand("cmd-2", "idem-2", ControlCapabilityNames.DISABLE, disable(), Optional.of(new Revision(1)), PRINCIPAL, 6),
                enabled.record());

        assertEquals(CapabilityEnablementDecisionStatus.ACCEPTED, enabled.status());
        assertTrue(enabled.record().state().binding(RANK).orElseThrow().enabled());
        assertFalse(disabled.record().state().binding(RANK).orElseThrow().enabled());
        assertEquals(new Revision(2), disabled.revision());
        assertTrue(enabled.emissions().stream().anyMatch(emission -> emission.kind() == CapabilityEnablementEmissionKind.CAPABILITY_ENABLED));
        assertTrue(disabled.emissions().stream().anyMatch(emission -> emission.kind() == CapabilityEnablementEmissionKind.CAPABILITY_DISABLED));

        CapabilityEnablementControlRecord replayed = CapabilityEnablementController.replay(
                SCOPE,
                6,
                List.of(enabled.events().getFirst(), disabled.events().getFirst()));
        assertEquals(disabled.record(), replayed);
    }

    @Test
    void duplicateIdempotencyReplaysWithoutEmissions() {
        CapabilityEnablementController controller = new CapabilityEnablementController();
        CapabilityEnablementControlRecord record = CapabilityEnablementController.emptyRecord(SCOPE, 6);
        CapabilityEnablementControlCommand<EnableCapability> command =
                controlCommand("cmd-3", "idem-3", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(0)), PRINCIPAL, 6);

        CapabilityEnablementDecision accepted = controller.handle(command, record);
        CapabilityEnablementDecision replayed = controller.handle(command, accepted.record());

        assertEquals(CapabilityEnablementDecisionStatus.ACCEPTED, accepted.status());
        assertEquals(CapabilityEnablementDecisionStatus.REPLAYED, replayed.status());
        assertEquals(accepted.receipt(), replayed.receipt());
        assertTrue(replayed.events().isEmpty());
        assertTrue(replayed.emissions().isEmpty());
    }

    @Test
    void staleOwnerCannotReplayStoredDecision() {
        CapabilityEnablementController controller = new CapabilityEnablementController();
        CapabilityEnablementControlCommand<EnableCapability> command =
                controlCommand("cmd-4", "idem-4", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(0)), PRINCIPAL, 6);
        CapabilityEnablementDecision accepted = controller.handle(command, CapabilityEnablementController.emptyRecord(SCOPE, 6));

        CapabilityEnablementDecision stale = controller.handle(
                controlCommand("cmd-4", "idem-4", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(0)), PRINCIPAL, 5),
                accepted.record());

        assertEquals(CapabilityEnablementDecisionStatus.REJECTED, stale.status());
        assertEquals(Optional.of(CapabilityEnablementRejectionReason.STALE_FENCING_EPOCH), stale.receipt().rejectionReason());
    }

    @Test
    void repeatedEnableWithNewIdempotencyKeyIsRejected() {
        CapabilityEnablementController controller = new CapabilityEnablementController();
        CapabilityEnablementDecision enabled = controller.handle(
                controlCommand("cmd-5", "idem-5", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(0)), PRINCIPAL, 6),
                CapabilityEnablementController.emptyRecord(SCOPE, 6));

        CapabilityEnablementDecision rejected = controller.handle(
                controlCommand("cmd-6", "idem-6", ControlCapabilityNames.ENABLE, enable(), Optional.of(new Revision(1)), PRINCIPAL, 6),
                enabled.record());

        assertEquals(CapabilityEnablementDecisionStatus.REJECTED, rejected.status());
        assertEquals(Optional.of(CapabilityEnablementRejectionReason.CAPABILITY_ALREADY_ENABLED), rejected.receipt().rejectionReason());
    }

    private static EnableCapability enable() {
        return new EnableCapability(SCOPE, RANK, "duel-contracts-v1", "policy", NOW, trace());
    }

    private static DisableCapability disable() {
        return new DisableCapability(SCOPE, RANK, "operator-disabled", NOW.plusSeconds(10), trace());
    }

    private static <T extends CapabilityEnablementCommand> CapabilityEnablementControlCommand<T> controlCommand(
            String commandId,
            String idempotencyKey,
            CommandName commandName,
            T payload,
            Optional<Revision> expectedRevision,
            PrincipalId authenticatedPrincipal,
            long fencingEpoch) {
        return new CapabilityEnablementControlCommand<>(
                new CommandEnvelope<>(
                        new CommandId(commandId),
                        new IdempotencyKey(idempotencyKey),
                        PRINCIPAL,
                        aggregateId(payload),
                        ControlCapabilityNames.CONTRACT,
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

    private static AggregateId aggregateId(CapabilityEnablementCommand payload) {
        return ControlCapabilityNames.aggregateId(payload.scope());
    }

    private static TraceEnvelope trace() {
        return new TraceEnvelope(
                "trace-capability",
                "span-capability",
                Optional.empty(),
                NOW,
                "capability-enablement-test",
                new InstanceId("instance-capability-test"));
    }
}
