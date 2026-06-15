package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityFencingCommandPortTest {
    @Test
    void stampsAuthorityEpochBeforeDelegating() {
        AtomicReference<DataAuthority.AuthorityCommand> received = new AtomicReference<>();
        AtomicReference<String> claimedDomain = new AtomicReference<>();
        AtomicReference<String> claimedPartitionKey = new AtomicReference<>();
        AtomicReference<AuthorityWriterClaim> writerClaim = new AtomicReference<>();
        DataAuthority.CommandPort delegate = command -> {
            received.set(command);
            return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                command.commandId(),
                true,
                1L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            ));
        };
        AuthorityFencingCommandPort port = new AuthorityFencingCommandPort(
            delegate,
            (commandDomain, commandTopic, partitionKey, ownerNode) -> {
                claimedDomain.set(commandDomain);
                claimedPartitionKey.set(partitionKey);
                assertThat(ownerNode).isEqualTo("registry-service");
                AuthorityWriterClaim claim = AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    partitionKey,
                    ownerNode,
                    42L,
                    null,
                    0L,
                    Instant.EPOCH
                );
                writerClaim.set(claim);
                return claim;
            },
            "registry-service"
        );

        DataAuthority.PlayerRankCommand command = rankCommand("999");

        DataAuthority.CommandResult result = port.submit(command).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(command.fencingToken()).isEqualTo("999");
        AuthorityWriterClaimToken token = AuthorityWriterClaimToken.parse(received.get().fencingToken());
        assertThat(token).isNotNull();
        assertThat(token.epoch()).isEqualTo(42L);
        assertThat(token.claimId()).isEqualTo(writerClaim.get().claimId());
        assertThat(token.claimFingerprint()).isEqualTo(writerClaim.get().claimFingerprint());
        assertThat(received.get().commandId()).isEqualTo(command.commandId());
        assertThat(received.get().expectedRevision()).isEqualTo(command.expectedRevision());
        AuthorityWriteCustody expectedCustody = AuthorityWriteCustody.fromCommand(command);
        assertThat(claimedDomain.get()).isEqualTo("rank");
        assertThat(claimedPartitionKey.get()).isEqualTo(expectedCustody.ownershipPartitionKey());
        assertThat(expectedCustody.routePartitionKey()).isEqualTo(command.scope());
    }

    @Test
    void rejectsWhenClaimDoesNotMatchCommandRoute() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityFencingCommandPort port = new AuthorityFencingCommandPort(
            command -> {
                delegated.set(true);
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    command.commandId(),
                    true,
                    1L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> AuthorityWriterClaim.mint(
                "player_profile",
                commandTopic,
                partitionKey,
                ownerNode,
                42L,
                null,
                0L,
                Instant.EPOCH
            ),
            "registry-service"
        );

        DataAuthority.CommandResult result = port.submit(rankCommand("")).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);
        assertThat(result.message()).contains("claim domain does not match");
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsWhenClaimUsesAggregateKeyInsteadOfAuthorityLane() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityFencingCommandPort port = new AuthorityFencingCommandPort(
            command -> {
                delegated.set(true);
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    command.commandId(),
                    true,
                    1L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> {
                DataAuthority.PlayerRankCommand command = rankCommand("");
                return AuthorityWriterClaim.mint(
                    commandDomain,
                    commandTopic,
                    command.scope(),
                    ownerNode,
                    42L,
                    null,
                    0L,
                    Instant.EPOCH
                );
            },
            "registry-service"
        );

        DataAuthority.CommandResult result = port.submit(rankCommand("")).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);
        assertThat(result.message()).contains("authority lane");
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsWhenEpochCannotBeClaimed() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityFencingCommandPort port = new AuthorityFencingCommandPort(
            command -> {
                delegated.set(true);
                return CompletableFuture.completedFuture(new DataAuthority.CommandResult(
                    command.commandId(),
                    true,
                    1L,
                    DataAuthority.RejectionReason.NONE,
                    "accepted"
                ));
            },
            (commandDomain, commandTopic, partitionKey, ownerNode) -> {
                throw new IllegalStateException("epoch store unavailable");
            },
            "registry-service"
        );

        DataAuthority.CommandResult result = port.submit(rankCommand("")).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.STORE_UNAVAILABLE);
        assertThat(result.message()).contains("epoch store unavailable");
        assertThat(delegated).isFalse();
    }

    private static DataAuthority.PlayerRankCommand rankCommand(String fencingToken) {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "GRANT_RANK",
                "rank-service",
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 60_000L,
                fencingToken,
                DataAuthority.ANY_REVISION
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }
}
