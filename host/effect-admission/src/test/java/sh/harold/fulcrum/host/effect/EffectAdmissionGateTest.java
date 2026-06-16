package sh.harold.fulcrum.host.effect;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.CapabilityId;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectTargetScope;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostInstanceKinds;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectAdmissionGateTest {
    private static final Instant NOW = Instant.parse("2026-06-16T12:00:00Z");
    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final HostResourceGrant RANK_COMMAND_GRANT =
            new HostResourceGrant(HostResourceFamily.TOPIC, HostAccessMode.PRODUCE, "cmd.rank");
    private static final CapabilityId RANK_CAPABILITY = new CapabilityId("standard.rank");

    private final EffectAdmissionGate gate = new EffectAdmissionGate(EffectAdmissionPolicy.of(
            new EffectAdmissionRule(EffectClass.AUTHORITY, "rank:", Optional.of(RANK_CAPABILITY), RANK_COMMAND_GRANT)));

    @Test
    void admitsPlatformEffectsWithMatchingPrincipalSessionScopeCapabilityAndGrant() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(identity()), rankEffect());

        assertEquals(EffectAdmissionStatus.ACCEPTED, receipt.status());
        assertTrue(receipt.rejectionReason().isEmpty());
        assertEquals(new PrincipalId("principal-host-paper-1"), receipt.authenticatedPrincipal());
        assertEquals(new IdempotencyKey("idem-rank-1"), receipt.idempotencyKey());
        assertEquals(Optional.of(RANK_CAPABILITY), receipt.requiredCapability());
        assertEquals(Optional.of(RANK_COMMAND_GRANT), receipt.requiredGrant());
    }

    @Test
    void refusedPlatformEffectsCarryReceiptWithScopePrincipalTraceAndIdempotencyEvidence() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of());

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(identity()), rankEffect());

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.MISSING_HOST_GRANT), receipt.rejectionReason());
        assertEquals(new PrincipalId("principal-host-paper-1"), receipt.authenticatedPrincipal());
        assertEquals(new EffectTargetScope("rank:subject-1"), receipt.targetScope());
        assertEquals(new IdempotencyKey("idem-rank-1"), receipt.idempotencyKey());
        assertEquals(new InstanceId("instance-paper-1"), receipt.traceEnvelope().originInstanceId());
        assertEquals(Optional.of(RANK_COMMAND_GRANT), receipt.requiredGrant());
    }

    @Test
    void rejectsForgedAttachmentPrincipalBeforeGrantCheck() {
        HostInstanceIdentity forgedIdentity = new HostInstanceIdentity(
                new InstanceId("instance-paper-1"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-small"),
                new MachineRef("machine-a"),
                new PrincipalId("principal-forged"));
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(forgedIdentity), rankEffect());

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.SECURITY_CONTEXT_MISMATCH), receipt.rejectionReason());
        assertTrue(receipt.requiredGrant().isEmpty());
    }

    @Test
    void rejectsStolenIdempotencyKeyFromAnotherSessionBeforeGrantCheck() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));
        EffectEnvelope<TestPayload> effect = rankEffect(new SessionId("session-other"), EffectSettlementMode.ACCEPTED_ASYNC);

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(identity()), effect);

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.ORIGIN_SESSION_MISMATCH), receipt.rejectionReason());
        assertEquals(new IdempotencyKey("idem-rank-1"), receipt.idempotencyKey());
    }

    @Test
    void rejectsCapabilityWideningAttemptForDeclaredScope() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));
        EffectEnvelope<TestPayload> effect = EffectEnvelope.issue(
                new EffectId("effect-rank-1"),
                new IdempotencyKey("idem-rank-1"),
                EffectOrigin.session(SESSION_ID),
                trace(new InstanceId("instance-paper-1")),
                Optional.of(new CapabilityId("standard.chat")),
                new EffectTargetScope("rank:subject-1"),
                EffectClass.AUTHORITY,
                new TestPayload("fixture.rank-effect", "grant"),
                NOW,
                Optional.empty(),
                EffectSettlementMode.ACCEPTED_ASYNC);

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(identity()), effect);

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.CAPABILITY_SCOPE_MISMATCH), receipt.rejectionReason());
        assertEquals(Optional.of(RANK_COMMAND_GRANT), receipt.requiredGrant());
    }

    @Test
    void rejectsPlatformEffectThatAsksForHostInlineSettlement() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));

        EffectAdmissionReceipt receipt = gate.admit(
                securityContext,
                attachment(identity()),
                rankEffect(SESSION_ID, EffectSettlementMode.HOST_INLINE));

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.INVALID_SETTLEMENT), receipt.rejectionReason());
    }

    @Test
    void hostLocalEffectsCannotEnterPlatformAdmission() {
        HostSecurityContext securityContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));
        EffectEnvelope<TestPayload> effect = EffectEnvelope.issue(
                new EffectId("effect-host-local-1"),
                new IdempotencyKey("idem-host-local-1"),
                EffectOrigin.session(SESSION_ID),
                trace(new InstanceId("instance-paper-1")),
                Optional.empty(),
                new EffectTargetScope("session:session-1"),
                EffectClass.HOST_LOCAL,
                new TestPayload("fixture.host-effect", "sound"),
                NOW,
                Optional.empty(),
                EffectSettlementMode.HOST_INLINE);

        EffectAdmissionReceipt receipt = gate.admit(securityContext, attachment(identity()), effect);

        assertEquals(EffectAdmissionStatus.REJECTED, receipt.status());
        assertEquals(Optional.of(EffectAdmissionRejectionReason.HOST_LOCAL_EFFECT), receipt.rejectionReason());
    }

    @Test
    void hostileHostSimulationRejectsEveryEscalationAttempt() {
        HostSecurityContext scopedContext = securityContext(HostCredentialScope.of(RANK_COMMAND_GRANT));
        HostSecurityContext unscopedContext = securityContext(HostCredentialScope.of());
        HostSessionAttachment attachedSession = attachment(identity());

        List<EffectAdmissionReceipt> receipts = List.of(
                gate.admit(unscopedContext, attachedSession, rankEffect()),
                gate.admit(scopedContext, attachment(new HostInstanceIdentity(
                        new InstanceId("instance-paper-1"),
                        HostInstanceKinds.PAPER,
                        new PoolId("pool-paper-small"),
                        new MachineRef("machine-a"),
                        new PrincipalId("principal-forged"))), rankEffect()),
                gate.admit(scopedContext, attachedSession, rankEffect(new SessionId("session-other"), EffectSettlementMode.ACCEPTED_ASYNC)),
                gate.admit(scopedContext, attachedSession, rankEffect(SESSION_ID, EffectSettlementMode.HOST_INLINE))
        );

        assertTrue(receipts.stream().allMatch(receipt -> receipt.status() == EffectAdmissionStatus.REJECTED));
        assertEquals(
                List.of(
                        Optional.of(EffectAdmissionRejectionReason.MISSING_HOST_GRANT),
                        Optional.of(EffectAdmissionRejectionReason.SECURITY_CONTEXT_MISMATCH),
                        Optional.of(EffectAdmissionRejectionReason.ORIGIN_SESSION_MISMATCH),
                        Optional.of(EffectAdmissionRejectionReason.INVALID_SETTLEMENT)),
                receipts.stream().map(EffectAdmissionReceipt::rejectionReason).toList());
    }

    private static EffectEnvelope<TestPayload> rankEffect() {
        return rankEffect(SESSION_ID, EffectSettlementMode.ACCEPTED_ASYNC);
    }

    private static EffectEnvelope<TestPayload> rankEffect(SessionId sessionId, EffectSettlementMode settlementMode) {
        return EffectEnvelope.issue(
                new EffectId("effect-rank-1"),
                new IdempotencyKey("idem-rank-1"),
                EffectOrigin.session(sessionId),
                trace(new InstanceId("instance-paper-1")),
                Optional.of(RANK_CAPABILITY),
                new EffectTargetScope("rank:subject-1"),
                EffectClass.AUTHORITY,
                new TestPayload("fixture.rank-effect", "grant"),
                NOW,
                Optional.empty(),
                settlementMode);
    }

    private static HostSecurityContext securityContext(HostCredentialScope scope) {
        return new HostSecurityContext(identity(), "service-account:paper-agent", scope);
    }

    private static HostSessionAttachment attachment(HostInstanceIdentity identity) {
        return new HostSessionAttachment(
                identity,
                new RouteId("route-1"),
                new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000801")),
                SESSION_ID,
                trace(identity.instanceId()),
                NOW);
    }

    private static HostInstanceIdentity identity() {
        return new HostInstanceIdentity(
                new InstanceId("instance-paper-1"),
                HostInstanceKinds.PAPER,
                new PoolId("pool-paper-small"),
                new MachineRef("machine-a"),
                new PrincipalId("principal-host-paper-1"));
    }

    private static TraceEnvelope trace(InstanceId instanceId) {
        return new TraceEnvelope(
                "trace-1",
                "span-1",
                Optional.empty(),
                NOW,
                "paper-agent",
                instanceId);
    }

    private record TestPayload(String payloadType, String value) implements EffectPayload {
    }
}
