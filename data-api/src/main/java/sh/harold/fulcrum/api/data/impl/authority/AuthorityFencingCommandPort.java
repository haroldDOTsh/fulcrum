package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stamps commands with the fencing epoch owned by this authority instance.
 */
public final class AuthorityFencingCommandPort implements DataAuthority.CommandPort {
    private final DataAuthority.CommandPort delegate;
    private final PartitionEpochStore epochStore;
    private final String ownerNode;

    public AuthorityFencingCommandPort(
        DataAuthority.CommandPort delegate,
        PartitionEpochStore epochStore,
        String ownerNode
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.epochStore = Objects.requireNonNull(epochStore, "epochStore");
        if (ownerNode == null || ownerNode.isBlank()) {
            throw new IllegalArgumentException("ownerNode is required");
        }
        this.ownerNode = ownerNode;
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        AuthorityWriteCustody custody = AuthorityWriteCustody.fromCommand(command);
        AuthorityWriterClaim claim;
        try {
            claim = epochStore.claimEpoch(
                custody.commandDomain(),
                custody.commandTopic(),
                custody.ownershipPartitionKey(),
                ownerNode
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(rejected(command, exception.getMessage()));
        }
        String invalidClaim = invalidClaim(custody, claim, ownerNode);
        if (invalidClaim != null) {
            return CompletableFuture.completedFuture(rejected(command, invalidClaim));
        }
        return delegate.submit(stamp(command, claim.fencingToken()));
    }

    static DataAuthority.AuthorityCommand stampWithClaim(
        DataAuthority.AuthorityCommand command,
        AuthorityWriterClaim claim
    ) {
        Objects.requireNonNull(claim, "claim");
        return stamp(command, claim.fencingToken());
    }

    private static String invalidClaim(
        AuthorityWriteCustody custody,
        AuthorityWriterClaim claim,
        String ownerNode
    ) {
        if (claim == null) {
            return "Authority fencing claim is missing";
        }
        if (!custody.commandDomain().equals(claim.commandDomain())) {
            return "Authority fencing claim domain does not match command route";
        }
        if (!custody.commandTopic().equals(claim.commandTopic())) {
            return "Authority fencing claim command topic does not match command route";
        }
        if (!custody.ownershipPartitionKey().equals(claim.partitionKey())) {
            return "Authority fencing claim partition key does not match authority lane";
        }
        if (!ownerNode.equals(claim.ownerNode())) {
            return "Authority fencing claim owner does not match authority node";
        }
        if (claim.epoch() <= 0L) {
            return "Authority fencing epoch must be positive";
        }
        return null;
    }

    private static DataAuthority.CommandResult rejected(
        DataAuthority.AuthorityCommand command,
        String message
    ) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            false,
            command.expectedRevision(),
            DataAuthority.RejectionReason.STORE_UNAVAILABLE,
            "Authority fencing epoch unavailable: " + (message == null ? "unknown" : message)
        );
    }

    private static DataAuthority.AuthorityCommand stamp(
        DataAuthority.AuthorityCommand command,
        String fencingToken
    ) {
        DataAuthority.CommandManifest manifest = manifest(command, fencingToken);
        if (command instanceof DataAuthority.PlayerProfileCommand profile) {
            return new DataAuthority.PlayerProfileCommand(
                manifest,
                profile.playerId(),
                profile.username(),
                profile.timestampEpochMillis(),
                profile.currentServer(),
                profile.currentProxy(),
                profile.lastIp(),
                profile.lastWorld(),
                profile.lastLocation(),
                profile.gameMode(),
                profile.level(),
                profile.exp(),
                profile.health(),
                profile.foodLevel(),
                profile.playtimeStartField()
            );
        }
        if (command instanceof DataAuthority.PlayerSessionCommand session) {
            return new DataAuthority.PlayerSessionCommand(
                manifest,
                session.playerId(),
                session.username(),
                session.sessionId(),
                session.timestampEpochMillis(),
                session.currentServer(),
                session.currentProxy(),
                session.lastIp(),
                session.protocolVersion(),
                session.disconnectReason()
            );
        }
        if (command instanceof DataAuthority.PlayerRankCommand rank) {
            return new DataAuthority.PlayerRankCommand(
                manifest,
                rank.playerId(),
                rank.primaryRank(),
                rank.ranks()
            );
        }
        if (command instanceof DataAuthority.MatchCommand match) {
            return new DataAuthority.MatchCommand(
                manifest,
                match.matchId(),
                match.familyId(),
                match.mapId(),
                match.serverId(),
                match.slotId(),
                match.state(),
                match.startedAtEpochMillis(),
                match.endedAtEpochMillis(),
                match.slotMetadata(),
                match.participants()
            );
        }
        throw new IllegalArgumentException("Unsupported authority command type: " + command.getClass().getName());
    }

    private static DataAuthority.CommandManifest manifest(
        DataAuthority.AuthorityCommand command,
        String fencingToken
    ) {
        DataAuthority.CommandManifest manifest = command.manifest();
        return new DataAuthority.CommandManifest(
            manifest.commandId(),
            manifest.declarationId(),
            manifest.actorId(),
            manifest.scope(),
            manifest.idempotencyKey(),
            manifest.deadlineEpochMillis(),
            fencingToken,
            manifest.expectedRevision(),
            manifest.schemaVersion(),
            manifest.provenance()
        );
    }

    public interface PartitionEpochStore {
        AuthorityWriterClaim claimEpoch(
            String commandDomain,
            String commandTopic,
            String partitionKey,
            String ownerNode
        );
    }
}
