package sh.harold.fulcrum.api.data.authority;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

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
}
