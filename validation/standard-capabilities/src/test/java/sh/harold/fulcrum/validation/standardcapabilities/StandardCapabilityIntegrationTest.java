package sh.harold.fulcrum.validation.standardcapabilities;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraph;
import sh.harold.fulcrum.capability.api.CapabilityDependencyGraphResolver;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.CapabilityValidationResult;
import sh.harold.fulcrum.capability.runtime.CapabilityContributionComposer;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlan;
import sh.harold.fulcrum.capability.runtime.CapabilityMaterializationPlanner;
import sh.harold.fulcrum.standard.chat.ChatDecorationCapability;
import sh.harold.fulcrum.standard.chat.ChatDecorationInput;
import sh.harold.fulcrum.standard.chat.ChatDecorationRenderer;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.PunishmentCapability;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginGate;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginRequest;
import sh.harold.fulcrum.standard.rank.EffectiveRankProjection;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankCapability;
import sh.harold.fulcrum.standard.rank.RankGranted;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardCapabilityIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-06-16T18:00:00Z");
    private static final SubjectId SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000701"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-suite-validation");

    @Test
    void tierOneDescriptorsIntegrateThroughDeclaredContractsAndClosedContributionPipelines() {
        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(tierOneDescriptors());
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(tierOneDescriptors());
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(Optional.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.providerOf(PlayerProfileContracts.CONTRACT));
        assertEquals(Optional.of(RankCapability.CAPABILITY_ID), graph.providerOf(RankContracts.CONTRACT));
        assertEquals(Optional.of(PunishmentCapability.CAPABILITY_ID),
                graph.providerOf(PunishmentContracts.CONTRACT));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(RankCapability.CAPABILITY_ID));
        assertEquals(List.of(RankCapability.CAPABILITY_ID),
                graph.dependenciesFor(ChatDecorationCapability.CAPABILITY_ID));
        assertTrue(graph.dependenciesFor(PunishmentCapability.CAPABILITY_ID).isEmpty());

        assertEquals(List.of(
                        PlayerProfileContracts.EFFECTIVE_PROJECTION,
                        RankContracts.EFFECTIVE_PROJECTION,
                        PunishmentContracts.ACTIVE_PROJECTION),
                plan.projections().stream()
                        .map(resource -> resource.declaration().relationName())
                        .toList());
        assertEquals(List.of(PunishmentCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_LOGIN_GATE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(RankCapability.CAPABILITY_ID, ChatDecorationCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
    }

    @Test
    void hostContributionsReadTypedProjectionSnapshotsWithoutCallingCapabilityAuthorities() {
        EffectiveRankProjection rankProjection = EffectiveRankProjection.rebuild(List.of(new RankGranted(
                new EffectiveRankSnapshot(SUBJECT, "Admin", "rank:Admin", PRINCIPAL, NOW),
                new Revision(1))));

        String renderedText = ChatDecorationRenderer.decorate(new ChatDecorationInput(
                SUBJECT,
                "Harold",
                rankProjection.row(SUBJECT).map(row -> row.snapshot().primaryRankKey()),
                "hello network")).renderedText();

        assertEquals("[Admin] Harold: hello network", renderedText);

        ActivePunishmentSnapshot activePunishment = new ActivePunishmentSnapshot(
                SUBJECT,
                "punishment-suite-validation",
                "ban evasion",
                PRINCIPAL,
                NOW,
                NOW.plusSeconds(60));

        assertFalse(PunishmentLoginGate.evaluate(
                new PunishmentLoginRequest(SUBJECT, NOW.plusSeconds(1)),
                Optional.of(activePunishment)).allowed());
    }

    private static List<sh.harold.fulcrum.capability.api.CapabilityDescriptor> tierOneDescriptors() {
        return List.of(
                PlayerProfileCapability.descriptor(),
                RankCapability.descriptor(),
                ChatDecorationCapability.descriptor(),
                PunishmentCapability.descriptor());
    }
}
