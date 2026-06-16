package sh.harold.fulcrum.standard.guild;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityCommandProcessor;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityEmission;
import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;
import sh.harold.fulcrum.data.authority.AuthorityMutationResult;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.IdempotencyLedger;
import sh.harold.fulcrum.standard.contracts.GuildContracts;

import java.util.List;
import java.util.Objects;

public final class GuildAuthority {
    private final AuthorityCommandProcessor<GuildState, CreateGuild, GuildReceipt> processor;

    public GuildAuthority(IdempotencyLedger<GuildState, GuildReceipt> idempotencyLedger) {
        this.processor = new AuthorityCommandProcessor<>(
                Objects.requireNonNull(idempotencyLedger, "idempotencyLedger"),
                GuildAuthority::rejectionReceipt,
                this::create);
    }

    public AuthorityDecision<GuildState, GuildReceipt> handle(
            AuthorityCommand<CreateGuild> command,
            AuthorityRecord<GuildState> currentRecord) {
        return processor.process(command, currentRecord);
    }

    public static AuthorityRecord<GuildState> emptyRecord(long fencingEpoch) {
        return new AuthorityRecord<>(new Revision(0), fencingEpoch, GuildState.empty());
    }

    public static AggregateId aggregateId(GuildId guildId) {
        Objects.requireNonNull(guildId, "guildId");
        return new AggregateId("guild:" + guildId.value());
    }

    public static String cacheKey(GuildId guildId) {
        return GuildContracts.CONTRACT.value() + ":" + aggregateId(guildId).value();
    }

    private AuthorityMutationResult<GuildState, GuildReceipt> create(
            AuthorityCommand<CreateGuild> command,
            AuthorityRecord<GuildState> currentRecord) {
        CreateGuild payload = command.envelope().payload();
        if (!command.envelope().aggregateId().equals(aggregateId(payload.guildId()))) {
            throw new IllegalArgumentException("guild aggregate must be keyed by Guild");
        }
        if (currentRecord.state().current().isPresent()) {
            throw new IllegalArgumentException("guild already exists for aggregate");
        }

        Revision nextRevision = new Revision(currentRecord.revision().value() + 1);
        GuildRosterSnapshot snapshot = new GuildRosterSnapshot(
                payload.guildId(),
                payload.ownerSubjectId(),
                payload.memberSubjectIds(),
                payload.displayName(),
                command.authenticatedPrincipal(),
                payload.createdAt());
        GuildState state = new GuildState(snapshot);
        GuildReceipt receipt = GuildReceipt.accepted(
                snapshot,
                nextRevision,
                command.fencingEpoch(),
                command.envelope().idempotencyKey().value(),
                command.envelope().commandId().value());
        GuildCreated event = new GuildCreated(snapshot, nextRevision);
        String aggregateKey = aggregateId(snapshot.guildId()).value();
        String statePayload = state.wireValue(nextRevision);
        return new AuthorityMutationResult<>(
                nextRevision,
                state,
                receipt,
                List.of(
                        new AuthorityEmission(AuthorityEmissionKind.EVENT, aggregateKey, event.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.STATE, aggregateKey, statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.PROJECTION, GuildContracts.ROSTER_PROJECTION + ":" + snapshot.guildId().value(), statePayload),
                        new AuthorityEmission(AuthorityEmissionKind.RESPONSE, command.envelope().commandId().value(), receipt.wireValue()),
                        new AuthorityEmission(AuthorityEmissionKind.CACHE_WRITE, cacheKey(snapshot.guildId()), statePayload)));
    }

    private static GuildReceipt rejectionReceipt(AuthorityRejectionReason reason) {
        return GuildReceipt.rejected(reason.name());
    }
}
