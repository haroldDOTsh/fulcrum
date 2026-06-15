package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityPrincipalCommandPortTest {
    @Test
    void rejectsReservedActorMismatchBeforeDelegate() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityPrincipalCommandPort port = new AuthorityPrincipalCommandPort(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });

        DataAuthority.CommandResult result = port.submit(rankCommand(
            "node:paper-2",
            new DataAuthority.CommandProvenance(
                "paper-1",
                "messagebus:paper-1->authority",
                "message-bus-provider",
                1,
                "node:paper-1"
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsMessageBusCommandWithoutVerifiedPrincipal() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityPrincipalCommandPort port = new AuthorityPrincipalCommandPort(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });

        DataAuthority.CommandResult result = port.submit(rankCommand(
            "rank-service",
            new DataAuthority.CommandProvenance(
                "unknown",
                "messagebus:unknown->authority",
                "message-bus-provider",
                1
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsKafkaCommandWithoutVerifiedPrincipal() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityPrincipalCommandPort port = new AuthorityPrincipalCommandPort(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });

        DataAuthority.CommandResult result = port.submit(rankCommand(
            "rank-service",
            new DataAuthority.CommandProvenance(
                "unknown",
                "kafka:unknown->authority",
                AuthorityPrincipalCommandPort.KAFKA_COMMAND_LOG_PROVIDER,
                1
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(delegated).isFalse();
    }

    @Test
    void rejectsTransportActorMismatchWithVerifiedPrincipal() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityPrincipalCommandPort port = new AuthorityPrincipalCommandPort(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });

        DataAuthority.CommandResult result = port.submit(rankCommand(
            "rank-service",
            new DataAuthority.CommandProvenance(
                "paper-1",
                "messagebus:paper-1->authority",
                "message-bus-provider",
                1,
                "node:paper-1"
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(DataAuthority.RejectionReason.INVALID_ACTOR);
        assertThat(delegated).isFalse();
    }

    @Test
    void allowsTransportActorMatchingVerifiedPrincipal() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        AuthorityPrincipalCommandPort port = new AuthorityPrincipalCommandPort(command -> {
            delegated.set(true);
            return CompletableFuture.completedFuture(accepted(command));
        });

        DataAuthority.CommandResult result = port.submit(rankCommand(
            "node:paper-1",
            new DataAuthority.CommandProvenance(
                "paper-1",
                "messagebus:paper-1->authority",
                "message-bus-provider",
                1,
                "node:paper-1"
            )
        )).toCompletableFuture().join();

        assertThat(result.accepted()).isTrue();
        assertThat(delegated).isTrue();
    }

    private static DataAuthority.PlayerRankCommand rankCommand(
        String actorId,
        DataAuthority.CommandProvenance provenance
    ) {
        UUID commandId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        return new DataAuthority.PlayerRankCommand(
            DataAuthority.CommandManifest.create(
                commandId,
                "GRANT_RANK",
                actorId,
                "rank:player:" + playerId,
                "rank:" + commandId,
                System.currentTimeMillis() + 1000L,
                "",
                DataAuthority.ANY_REVISION,
                provenance
            ),
            playerId,
            "ADMIN",
            List.of("DEFAULT", "ADMIN")
        );
    }

    private static DataAuthority.CommandResult accepted(DataAuthority.AuthorityCommand command) {
        return new DataAuthority.CommandResult(
            command.commandId(),
            true,
            1L,
            DataAuthority.RejectionReason.NONE,
            "accepted"
        );
    }
}
