package sh.harold.fulcrum.host.paper;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperLobbyProofMessageTest {
    @Test
    void proofMessageRoundTripsSpawnProfileRankAndChatDecoration() {
        SubjectId subjectId = new SubjectId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        PaperLobbyProofMessage message = PaperLobbyProofMessage.from(
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new PaperSpawnPoint("world", 0.5D, 65.0D, 0.5D, 0.0F, 0.0F),
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F,
                new PaperSubjectCapabilityView(subjectId, "Fulcrum Bot One", Optional.of("Admin")),
                new PaperChatDecorationResponse(subjectId, "[Admin] Fulcrum Bot One: fulcrum-proof-chat"));

        PaperLobbyProofMessage decoded = PaperLobbyProofMessage.parse(
                new String(message.encode(), StandardCharsets.UTF_8));

        assertEquals(new InstanceId("paper-instance-lobby-one"), decoded.instanceId());
        assertEquals(new SessionId("session-lobby-shared"), decoded.sessionId());
        assertEquals(subjectId, decoded.subjectId());
        assertEquals("world", decoded.spawnWorld());
        assertEquals(0, decoded.bedrockBlockX());
        assertEquals(64, decoded.bedrockBlockY());
        assertEquals(0, decoded.bedrockBlockZ());
        assertEquals(0.5D, decoded.playerX());
        assertEquals(65.0D, decoded.playerY());
        assertEquals(0.5D, decoded.playerZ());
        assertEquals(0.0F, decoded.playerYaw());
        assertEquals(0.0F, decoded.playerPitch());
        assertEquals("Fulcrum Bot One", decoded.displayName());
        assertEquals("Admin", decoded.rankLabel().orElseThrow());
        assertEquals("[Admin] Fulcrum Bot One: fulcrum-proof-chat", decoded.decoratedChat());
        assertTrue(message.asText().startsWith(PaperLobbyProofMessage.MARKER));
    }

    @Test
    void proofParserFindsMarkerInsideMinecraftCustomPayloadBytes() {
        PaperLobbyProofMessage message = new PaperLobbyProofMessage(
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                new SubjectId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")),
                "world",
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F,
                "Fulcrum Bot Two",
                Optional.empty(),
                "Fulcrum Bot Two: fulcrum-proof-chat");

        PaperLobbyProofMessage decoded = PaperLobbyProofMessage.parse(
                "packet-prefix\0" + message.asText());

        assertEquals(new InstanceId("paper-instance-lobby-one"), decoded.instanceId());
        assertEquals(new SessionId("session-lobby-shared"), decoded.sessionId());
        assertEquals("Fulcrum Bot Two", decoded.displayName());
        assertTrue(decoded.rankLabel().isEmpty());
        assertEquals("Fulcrum Bot Two: fulcrum-proof-chat", decoded.decoratedChat());
    }
}
