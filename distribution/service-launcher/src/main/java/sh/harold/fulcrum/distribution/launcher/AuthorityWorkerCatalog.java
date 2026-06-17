package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataAuthority;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataReceipt;
import sh.harold.fulcrum.data.artifact.ArtifactMetadataState;
import sh.harold.fulcrum.data.artifact.PublishArtifactMetadata;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDomainHandler;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.presence.PresenceAuthority;
import sh.harold.fulcrum.data.presence.PresenceCommand;
import sh.harold.fulcrum.data.presence.PresenceReceipt;
import sh.harold.fulcrum.data.presence.PresenceState;
import sh.harold.fulcrum.data.route.RouteAuthority;
import sh.harold.fulcrum.data.route.RouteReceipt;
import sh.harold.fulcrum.data.route.RouteState;
import sh.harold.fulcrum.data.route.contract.RouteCommand;
import sh.harold.fulcrum.data.session.SessionAuthority;
import sh.harold.fulcrum.data.session.SessionCommand;
import sh.harold.fulcrum.data.session.SessionReceipt;
import sh.harold.fulcrum.data.session.SessionState;
import sh.harold.fulcrum.data.subject.SubjectAuthority;
import sh.harold.fulcrum.data.subject.SubjectCommand;
import sh.harold.fulcrum.data.subject.SubjectReceipt;
import sh.harold.fulcrum.data.subject.SubjectState;
import sh.harold.fulcrum.standard.profile.PlayerProfileAuthority;
import sh.harold.fulcrum.standard.profile.PlayerProfileReceipt;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentAuthority;
import sh.harold.fulcrum.standard.punishment.PunishmentReceipt;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankAuthority;
import sh.harold.fulcrum.standard.rank.RankReceipt;
import sh.harold.fulcrum.standard.rank.RankState;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class AuthorityWorkerCatalog {
    private static final String SUBJECT = "subject";
    private static final String PRESENCE = "presence";
    private static final String ROUTE = "route";
    private static final String SESSION = "session";
    private static final String ARTIFACT_METADATA = "artifact-metadata";
    private static final String PLAYER_PROFILE = StandardCapabilityAuthorityWireCodec.PLAYER_PROFILE_DOMAIN;
    private static final String RANK = StandardCapabilityAuthorityWireCodec.RANK_DOMAIN;
    private static final String PUNISHMENT = StandardCapabilityAuthorityWireCodec.PUNISHMENT_DOMAIN;

    private final AuthorityRuntimeBindings bindings;
    private final long fencingEpoch;

    AuthorityWorkerCatalog(AuthorityRuntimeBindings bindings, long fencingEpoch) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        if (fencingEpoch < 0) {
            throw new IllegalArgumentException("fencingEpoch must be non-negative");
        }
        this.fencingEpoch = fencingEpoch;
    }

    static List<String> authorityDomains() {
        return List.of(SUBJECT, PRESENCE, ROUTE, SESSION, ARTIFACT_METADATA, PLAYER_PROFILE, RANK, PUNISHMENT);
    }

    List<AuthorityWorkerBinding> workerBindings() {
        return List.of(
                this.<SubjectState, SubjectCommand, SubjectReceipt>worker(SUBJECT, () -> SubjectAuthority.emptyRecord(fencingEpoch),
                        ledger -> new SubjectAuthority(ledger)::handle),
                this.<PresenceState, PresenceCommand, PresenceReceipt>worker(PRESENCE, () -> PresenceAuthority.emptyRecord(fencingEpoch),
                        ledger -> new PresenceAuthority(ledger)::handle),
                this.<RouteState, RouteCommand, RouteReceipt>worker(ROUTE, () -> RouteAuthority.emptyRecord(fencingEpoch),
                        ledger -> new RouteAuthority(ledger)::handle),
                this.<SessionState, SessionCommand, SessionReceipt>worker(SESSION, () -> SessionAuthority.emptyRecord(fencingEpoch),
                        ledger -> new SessionAuthority(ledger)::handle),
                this.<ArtifactMetadataState, PublishArtifactMetadata, ArtifactMetadataReceipt>worker(ARTIFACT_METADATA, () -> ArtifactMetadataAuthority.emptyRecord(fencingEpoch),
                        ledger -> new ArtifactMetadataAuthority(ledger)::handle),
                this.<PlayerProfileState, UpsertPlayerProfile, PlayerProfileReceipt>worker(PLAYER_PROFILE, () -> PlayerProfileAuthority.emptyRecord(fencingEpoch),
                        ledger -> new PlayerProfileAuthority(ledger)::handle),
                this.<RankState, GrantRank, RankReceipt>worker(RANK, () -> RankAuthority.emptyRecord(fencingEpoch),
                        ledger -> new RankAuthority(ledger)::handle),
                this.<PunishmentState, IssuePunishment, PunishmentReceipt>worker(PUNISHMENT, () -> PunishmentAuthority.emptyRecord(fencingEpoch),
                        ledger -> new PunishmentAuthority(ledger)::handle));
    }

    private <S, C extends CommandPayload, R> AuthorityWorkerBinding worker(
            String authorityDomain,
            Supplier<AuthorityRecord<S>> emptyRecord,
            Function<IdempotencyLedger<S, R>, AuthorityDomainHandler<S, C, R>> handlerFactory) {
        AuthorityRuntimeWorker<S, C, R> worker = new AuthorityRuntimeWorker<>(
                bindings.commandSource(authorityDomain),
                bindings.recordStore(authorityDomain, emptyRecord),
                handlerFactory.apply(bindings.idempotencyLedger(authorityDomain)),
                bindings.projectionWriter(authorityDomain),
                bindings.emissionSink(authorityDomain),
                bindings.decisionRecorder(authorityDomain),
                bindings.offsetCommitter(authorityDomain));
        return AuthorityWorkerBinding.fromWorker(authorityDomain, worker);
    }
}
