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
import sh.harold.fulcrum.standard.contracts.EconomyContracts;
import sh.harold.fulcrum.standard.contracts.PartyContracts;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.contracts.FriendsContracts;
import sh.harold.fulcrum.standard.contracts.StatsContracts;
import sh.harold.fulcrum.standard.economy.EconomyAccountId;
import sh.harold.fulcrum.standard.economy.EconomyCapability;
import sh.harold.fulcrum.standard.economy.EconomyLedgerEntry;
import sh.harold.fulcrum.standard.economy.EconomyLedgerEntryRecorded;
import sh.harold.fulcrum.standard.economy.EconomyProjection;
import sh.harold.fulcrum.standard.friends.FriendInviteAccepted;
import sh.harold.fulcrum.standard.friends.FriendsCapability;
import sh.harold.fulcrum.standard.friends.FriendsProjection;
import sh.harold.fulcrum.standard.friends.FriendConnectionSnapshot;
import sh.harold.fulcrum.standard.party.PartyCapability;
import sh.harold.fulcrum.standard.party.PartyFormed;
import sh.harold.fulcrum.standard.party.PartyId;
import sh.harold.fulcrum.standard.party.PartyRosterProjection;
import sh.harold.fulcrum.standard.party.PartyRosterSnapshot;
import sh.harold.fulcrum.standard.contracts.GuildContracts;
import sh.harold.fulcrum.standard.guild.GuildCapability;
import sh.harold.fulcrum.standard.guild.GuildCreated;
import sh.harold.fulcrum.standard.guild.GuildId;
import sh.harold.fulcrum.standard.guild.GuildRosterProjection;
import sh.harold.fulcrum.standard.guild.GuildRosterSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileCapability;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.PunishmentCapability;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginGate;
import sh.harold.fulcrum.standard.punishment.PunishmentLoginRequest;
import sh.harold.fulcrum.standard.rank.EffectiveRankProjection;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.RankCapability;
import sh.harold.fulcrum.standard.rank.RankGranted;
import sh.harold.fulcrum.standard.stats.StatsCapability;
import sh.harold.fulcrum.standard.stats.StatsCounterId;
import sh.harold.fulcrum.standard.stats.StatsDeltaRecorded;
import sh.harold.fulcrum.standard.stats.StatsLedgerEntry;
import sh.harold.fulcrum.standard.stats.StatsProjection;

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
    private static final SubjectId PARTY_MEMBER = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000702"));
    private static final SubjectId FRIEND_SUBJECT = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000703"));
    private static final SubjectId GUILD_MEMBER = new SubjectId(UUID.fromString("00000000-0000-0000-0000-000000000704"));
    private static final PrincipalId PRINCIPAL = new PrincipalId("standard-suite-validation");

    @Test
    void tierOneTierTwoAndEconomyDescriptorsIntegrateThroughDeclaredContractsAndClosedContributionPipelines() {
        CapabilityValidationResult graphResult = CapabilityDependencyGraphResolver.validate(standardDescriptorsWithEconomy());
        CapabilityDependencyGraph graph = CapabilityDependencyGraphResolver.resolve(standardDescriptorsWithEconomy());
        CapabilityMaterializationPlan plan = CapabilityMaterializationPlanner.plan(graph);

        assertTrue(graphResult.valid(), () -> graphResult.errors().toString());
        assertEquals(Optional.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.providerOf(PlayerProfileContracts.CONTRACT));
        assertEquals(Optional.of(RankCapability.CAPABILITY_ID), graph.providerOf(RankContracts.CONTRACT));
        assertEquals(Optional.of(PartyCapability.CAPABILITY_ID), graph.providerOf(PartyContracts.CONTRACT));
        assertEquals(Optional.of(FriendsCapability.CAPABILITY_ID), graph.providerOf(FriendsContracts.CONTRACT));
        assertEquals(Optional.of(GuildCapability.CAPABILITY_ID), graph.providerOf(GuildContracts.CONTRACT));
        assertEquals(Optional.of(EconomyCapability.CAPABILITY_ID), graph.providerOf(EconomyContracts.CONTRACT));
        assertEquals(Optional.of(StatsCapability.CAPABILITY_ID), graph.providerOf(StatsContracts.CONTRACT));
        assertEquals(Optional.of(PunishmentCapability.CAPABILITY_ID),
                graph.providerOf(PunishmentContracts.CONTRACT));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(RankCapability.CAPABILITY_ID));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(PartyCapability.CAPABILITY_ID));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(FriendsCapability.CAPABILITY_ID));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(GuildCapability.CAPABILITY_ID));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(EconomyCapability.CAPABILITY_ID));
        assertEquals(List.of(PlayerProfileCapability.CAPABILITY_ID),
                graph.dependenciesFor(StatsCapability.CAPABILITY_ID));
        assertEquals(List.of(RankCapability.CAPABILITY_ID),
                graph.dependenciesFor(ChatDecorationCapability.CAPABILITY_ID));
        assertTrue(graph.dependenciesFor(PunishmentCapability.CAPABILITY_ID).isEmpty());

        assertEquals(List.of(
                        PlayerProfileContracts.EFFECTIVE_PROJECTION,
                        RankContracts.EFFECTIVE_PROJECTION,
                        PartyContracts.ROSTER_PROJECTION,
                        PartyContracts.SUBJECT_INDEX_PROJECTION,
                        FriendsContracts.CONNECTION_PROJECTION,
                        FriendsContracts.SUBJECT_INDEX_PROJECTION,
                        GuildContracts.ROSTER_PROJECTION,
                        GuildContracts.SUBJECT_INDEX_PROJECTION,
                        EconomyContracts.BALANCE_PROJECTION,
                        EconomyContracts.LEDGER_PROJECTION,
                        StatsContracts.COUNTER_PROJECTION,
                        StatsContracts.EXPERIENCE_COUNTER_PROJECTION,
                        StatsContracts.LEDGER_PROJECTION,
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
        assertEquals(List.of(PartyCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.EXPERIENCE_QUEUE_POLICY)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(PartyCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.EXPERIENCE_ROSTER_POLICY)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(RankCapability.CAPABILITY_ID, ChatDecorationCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_CHAT_PIPELINE)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(FriendsCapability.CAPABILITY_ID, GuildCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PROXY_PLAYER_FANOUT)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(GuildCapability.CAPABILITY_ID, EconomyCapability.CAPABILITY_ID, StatsCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_MENUS)
                        .stream()
                        .map(CapabilityMaterializationPlan.ContributionRegistration::capabilityId)
                        .toList());
        assertEquals(List.of(EconomyCapability.CAPABILITY_ID, StatsCapability.CAPABILITY_ID),
                CapabilityContributionComposer.compose(plan, CapabilityScope.NETWORK)
                        .registrationsFor(CapabilityExtensionPoint.PAPER_SCOREBOARD)
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

    @Test
    void partyProjectionFeedsQueueRosterLogicWithoutCallingPartyAuthority() {
        PartyId partyId = new PartyId("party-suite-validation");
        PartyRosterProjection projection = PartyRosterProjection.rebuild(List.of(new PartyFormed(
                new PartyRosterSnapshot(partyId, SUBJECT, List.of(SUBJECT, PARTY_MEMBER), PRINCIPAL, NOW),
                new Revision(1))));

        assertEquals(partyId, projection.partyFor(PARTY_MEMBER).orElseThrow());
        assertEquals(List.of(SUBJECT, PARTY_MEMBER), projection.membersFor(SUBJECT));
    }

    @Test
    void friendsProjectionFeedsFanoutLogicWithoutCallingFriendsAuthority() {
        FriendsProjection projection = FriendsProjection.rebuild(List.of(new FriendInviteAccepted(
                FriendConnectionSnapshot.accepted(SUBJECT, FRIEND_SUBJECT, PRINCIPAL, NOW),
                new Revision(1))));

        assertEquals(List.of(FRIEND_SUBJECT), projection.friendsOf(SUBJECT));
        assertEquals(List.of(SUBJECT), projection.friendsOf(FRIEND_SUBJECT));
    }

    @Test
    void guildProjectionFeedsSocialSurfaceLogicWithoutCallingGuildAuthority() {
        GuildId guildId = new GuildId("guild-suite-validation");
        GuildRosterProjection projection = GuildRosterProjection.rebuild(List.of(new GuildCreated(
                new GuildRosterSnapshot(guildId, SUBJECT, List.of(SUBJECT, GUILD_MEMBER), "Suite Guild", PRINCIPAL, NOW),
                new Revision(1))));

        assertEquals(guildId, projection.guildFor(GUILD_MEMBER).orElseThrow());
        assertEquals(List.of(SUBJECT, GUILD_MEMBER), projection.membersFor(SUBJECT));
    }

    @Test
    void economyProjectionFeedsBalanceSurfacesFromAuditableLedgerWithoutCallingEconomyAuthority() {
        EconomyAccountId accountId = new EconomyAccountId(SUBJECT, "coins");
        EconomyProjection projection = EconomyProjection.rebuild(List.of(new EconomyLedgerEntryRecorded(
                new EconomyLedgerEntry(
                        "economy-suite-entry-1",
                        accountId,
                        100,
                        100,
                        "suite-reward",
                        PRINCIPAL,
                        NOW,
                        "economy-suite-idem-1",
                        "economy-suite-command-1",
                        new Revision(1)),
                new Revision(1))));

        assertEquals(100, projection.balance(accountId).orElseThrow().balanceMinorUnits());
        assertEquals(List.of("economy-suite-entry-1"), projection.ledgerEntriesFor(accountId).stream()
                .map(row -> row.entryId())
                .toList());
    }

    @Test
    void statsProjectionFeedsCrossExperienceSurfacesWithoutCallingStatsAuthority() {
        StatsCounterId counterId = new StatsCounterId(SUBJECT, "session-completions");
        sh.harold.fulcrum.api.kernel.ExperienceId arena = new sh.harold.fulcrum.api.kernel.ExperienceId("experience.suite.arena");
        sh.harold.fulcrum.api.kernel.ExperienceId realm = new sh.harold.fulcrum.api.kernel.ExperienceId("experience.suite.realm");
        StatsProjection projection = StatsProjection.rebuild(List.of(
                new StatsDeltaRecorded(new StatsLedgerEntry(
                        "stats-suite-entry-1",
                        counterId,
                        arena,
                        1,
                        1,
                        PRINCIPAL,
                        NOW,
                        "stats-suite-idem-1",
                        "stats-suite-command-1",
                        new Revision(1)), new Revision(1)),
                new StatsDeltaRecorded(new StatsLedgerEntry(
                        "stats-suite-entry-2",
                        counterId,
                        realm,
                        1,
                        2,
                        PRINCIPAL,
                        NOW.plusSeconds(1),
                        "stats-suite-idem-2",
                        "stats-suite-command-2",
                        new Revision(2)), new Revision(2))));

        assertEquals(2, projection.counter(counterId).orElseThrow().total());
        assertEquals(1, projection.experienceCounter(counterId, arena).orElseThrow().total());
        assertEquals(1, projection.experienceCounter(counterId, realm).orElseThrow().total());
    }

    private static List<sh.harold.fulcrum.capability.api.CapabilityDescriptor> standardDescriptorsWithEconomy() {
        return List.of(
                PlayerProfileCapability.descriptor(),
                RankCapability.descriptor(),
                ChatDecorationCapability.descriptor(),
                PartyCapability.descriptor(),
                FriendsCapability.descriptor(),
                GuildCapability.descriptor(),
                EconomyCapability.descriptor(),
                StatsCapability.descriptor(),
                PunishmentCapability.descriptor());
    }
}
