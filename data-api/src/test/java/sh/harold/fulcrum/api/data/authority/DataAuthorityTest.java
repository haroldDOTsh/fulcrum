package sh.harold.fulcrum.api.data.authority;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAuthorityTest {
    @Test
    void commandEnvelopeCopiesPayload() {
        UUID commandId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("rank", "ADMIN");

        DataAuthority.CommandEnvelope envelope = new DataAuthority.CommandEnvelope(
            commandId,
            DataAuthority.CommandType.GRANT_RANK,
            "rank-service",
            "player:" + UUID.randomUUID(),
            commandId.toString(),
            System.currentTimeMillis() + 1000,
            "fence-1",
            7L,
            payload
        );

        assertThat(envelope.payload()).containsEntry("rank", "ADMIN");
        assertThatThrownBy(() -> envelope.payload().put("rank", "DEFAULT"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandEnvelopeRequiresAuthorityFields() {
        assertThatThrownBy(() -> new DataAuthority.CommandEnvelope(
            UUID.randomUUID(),
            DataAuthority.CommandType.START_SESSION,
            "",
            "player:" + UUID.randomUUID(),
            "session-1",
            System.currentTimeMillis() + 1000,
            "",
            0L,
            Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actorId");
    }

    @Test
    void profileSnapshotCopiesProfileData() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            null,
            true,
            "lobby-1",
            "proxy-1",
            1000L,
            Map.of("lastWorld", "world"),
            3L
        );

        assertThat(snapshot.normalizedUsername()).isEqualTo("notch");
        assertThat(snapshot.profileData()).containsEntry("lastWorld", "world");
        assertThatThrownBy(() -> snapshot.profileData().put("lastWorld", "nether"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rankSnapshotDefaultsMissingRanks() {
        UUID playerId = UUID.randomUUID();

        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            "ADMIN",
            null,
            2L
        );

        assertThat(snapshot.ranks()).containsExactly("ADMIN");
    }

    @Test
    void profileReaderCanCheckExistence() {
        UUID playerId = UUID.randomUUID();
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            "Notch",
            "notch",
            false,
            null,
            null,
            0L,
            Map.of(),
            1L
        );

        DataAuthority.PlayerProfileReader reader = id ->
            CompletableFuture.completedFuture(id.equals(playerId) ? Optional.of(snapshot) : Optional.empty());

        assertThat(reader.profileExists(playerId).toCompletableFuture().join()).isTrue();
        assertThat(reader.profileExists(UUID.randomUUID()).toCompletableFuture().join()).isFalse();
    }
}
