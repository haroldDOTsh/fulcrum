package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.LoginAttemptResult;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.MinecraftStatusSnapshot;
import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftStatusClientTest {
    @Test
    void statusClientSpeaksMinecraftStatusProtocol() throws Exception {
        try (FakeMinecraftStatusServer server = FakeMinecraftStatusServer.start()) {
            MinecraftStatusSnapshot status = new MinecraftStatusClient().status(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    0,
                    Duration.ofSeconds(2));

            assertEquals("Fulcrum Test Velocity", status.versionName());
            assertEquals(767, status.protocolVersion());
            assertEquals(2, status.onlinePlayers());
            assertEquals(100, status.maxPlayers());
            assertTrue(status.rawJson().contains("Fulcrum lobby"));
        }
    }

    @Test
    void loginProbeAcceptsOfflineModeLoginSuccess() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOGIN_ACCEPTED)) {
            LoginAttemptResult result = new MinecraftStatusClient().login(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertTrue(result.accepted());
            assertEquals("FulcrumBotOne", result.username());
        }
    }

    @Test
    void loginProbeReturnsDisconnectReasonForDeniedLogin() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOGIN_DENIED)) {
            LoginAttemptResult result = new MinecraftStatusClient().login(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "BannedFulcrumBot",
                    Duration.ofSeconds(2));

            assertFalse(result.accepted());
            assertTrue(result.denialReason().orElseThrow().contains("Banned by Fulcrum"));
        }
    }

    @Test
    void lobbyProofProbeCompletesConfigurationAndReadsPaperPluginMessage() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_ACCEPTED)) {
            PaperLobbyProofMessage proof = new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2));

            assertEquals(new InstanceId("paper-instance-lobby-one"), proof.instanceId());
            assertEquals(new SessionId("session-lobby-shared"), proof.sessionId());
            assertEquals("world", proof.spawnWorld());
            assertEquals(0, proof.bedrockBlockX());
            assertEquals(64, proof.bedrockBlockY());
            assertEquals(0, proof.bedrockBlockZ());
            assertEquals(0.5D, proof.playerX());
            assertEquals(65.0D, proof.playerY());
            assertEquals(0.5D, proof.playerZ());
            assertEquals(0.0F, proof.playerYaw());
            assertEquals(0.0F, proof.playerPitch());
            assertEquals("Fulcrum Bot One", proof.displayName());
            assertEquals("Admin", proof.rankLabel().orElseThrow());
            assertEquals("[Admin] Fulcrum Bot One: fulcrum-proof-chat", proof.decoratedChat());
        }
    }

    @Test
    void lobbyProofProbeRejectsProofBeforePlayState() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.LOBBY_PROOF_BEFORE_PLAY)) {
            IOException exception = assertThrows(IOException.class, () -> new MinecraftStatusClient().lobbyProof(
                    new InetSocketAddress("127.0.0.1", server.port()),
                    767,
                    "FulcrumBotOne",
                    Duration.ofSeconds(2)));

            assertTrue(exception.getMessage().contains("before Minecraft client reached play state"));
        }
    }

    @Test
    void verifierAcceptsExplicitEndpointForClusterGate() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierDefaultsAgonesFleetStateCheckToKubernetesResolvedRuns() {
        LobbyClusterE2eVerifier.VerificationConfig kubernetesResolved =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{});
        LobbyClusterE2eVerifier.VerificationConfig explicitEndpoint =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                        "--endpoint-host=127.0.0.1"
                });
        LobbyClusterE2eVerifier.VerificationConfig forcedExplicitEndpoint =
                LobbyClusterE2eVerifier.VerificationConfig.parse(new String[]{
                        "--endpoint-host=127.0.0.1",
                        "--verify-agones-fleet-state=true",
                        "--agones-fleet-name=test-lobby-fleet",
                        "--expected-agones-allocated-replicas=2"
                });

        assertTrue(kubernetesResolved.verifyAgonesFleetState());
        assertFalse(explicitEndpoint.verifyAgonesFleetState());
        assertTrue(forcedExplicitEndpoint.verifyAgonesFleetState());
        assertEquals("test-lobby-fleet", forcedExplicitEndpoint.agonesFleetName());
        assertEquals(2, forcedExplicitEndpoint.expectedAgonesAllocatedReplicas());
    }

    @Test
    void verifierCanAssertDeniedLoginProbeForPunishmentGate() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED,
                ExchangeKind.LOGIN_DENIED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--login-username=FulcrumBotOne",
                    "--denied-login-username=BannedFulcrumBot",
                    "--denied-login-reason-contains=Banned by Fulcrum",
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierCanAssertScaleOutAfterSharedLobbyFills() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_ACCEPTED,
                ExchangeKind.LOGIN_DENIED_NO_ROUTE,
                ExchangeKind.LOBBY_PROOF_SCALE_OUT_ACCEPTED)) {
            LobbyClusterE2eVerifier.main(new String[]{
                    "--endpoint-host=127.0.0.1",
                    "--endpoint-port=" + server.port(),
                    "--verify-scale-out=true",
                    "--scale-out-timeout=PT2S",
                    "--timeout=PT2S"
            });
        }
    }

    @Test
    void verifierRejectsSecondAcceptedLoginOnDifferentSession() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_ACCEPTED,
                ExchangeKind.LOBBY_PROOF_SECOND_DIFFERENT_SESSION)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("Expected second accepted login to join Session"));
        }
    }

    @Test
    void verifierRejectsMismatchedLobbySpawnPosition() throws Exception {
        try (FakeMinecraftClusterServer server = FakeMinecraftClusterServer.start(
                ExchangeKind.STATUS,
                ExchangeKind.LOBBY_PROOF_WRONG_SPAWN)) {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    LobbyClusterE2eVerifier.main(new String[]{
                            "--endpoint-host=127.0.0.1",
                            "--endpoint-port=" + server.port(),
                            "--timeout=PT2S"
                    }));

            assertTrue(exception.getMessage().contains("playerY"));
        }
    }

    private static final class FakeMinecraftStatusServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Future<Void> server;

        private FakeMinecraftStatusServer(ServerSocket serverSocket, Future<Void> server) {
            this.serverSocket = serverSocket;
            this.server = server;
        }

        private static FakeMinecraftStatusServer start() throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            var executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(new StatusExchange(serverSocket));
            executor.shutdown();
            return new FakeMinecraftStatusServer(serverSocket, server);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            server.get(2, TimeUnit.SECONDS);
        }
    }

    private static final class FakeMinecraftClusterServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Future<Void> server;

        private FakeMinecraftClusterServer(ServerSocket serverSocket, Future<Void> server) {
            this.serverSocket = serverSocket;
            this.server = server;
        }

        private static FakeMinecraftClusterServer start(ExchangeKind... exchanges) throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, exchanges.length, InetAddress.getByName("127.0.0.1"));
            var executor = Executors.newSingleThreadExecutor();
            Future<Void> server = executor.submit(() -> {
                for (ExchangeKind exchange : exchanges) {
                    try (Socket socket = serverSocket.accept()) {
                        socket.setSoTimeout(2_000);
                        switch (exchange) {
                            case STATUS -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 0, 1);
                                verifyStatusRequest(readPacket(socket.getInputStream()));
                                writeStatusResponse(socket);
                            }
                            case LOGIN_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                            }
                            case LOBBY_PROOF_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_BEFORE_PLAY -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_SECOND_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotTwo", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotTwo");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo").value(),
                                        "Fulcrum Bot Two",
                                        "[Admin] Fulcrum Bot Two: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_SECOND_DIFFERENT_SESSION -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotTwo", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotTwo");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-other"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotTwo").value(),
                                        "Fulcrum Bot Two",
                                        "[Admin] Fulcrum Bot Two: fulcrum-proof-chat");
                            }
                            case LOBBY_PROOF_WRONG_SPAWN -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotOne", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotOne");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-one"),
                                        new SessionId("session-lobby-shared"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotOne").value(),
                                        "Fulcrum Bot One",
                                        "[Admin] Fulcrum Bot One: fulcrum-proof-chat",
                                        0,
                                        64,
                                        0,
                                        0.5D,
                                        66.0D,
                                        0.5D,
                                        0.0F,
                                        0.0F);
                            }
                            case LOGIN_DENIED_NO_ROUTE -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotThree", readLoginStart(readPacket(socket.getInputStream())));
                                writeLoginDisconnect(socket, "{\"text\":\"No lobby route is currently available\"}");
                            }
                            case LOBBY_PROOF_SCALE_OUT_ACCEPTED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("FulcrumBotFour", readLoginStart(readPacket(socket.getInputStream())));
                                writeCompressionThenLoginSuccess(socket, "FulcrumBotFour");
                                writeConfigurationFinish(socket);
                                verifyConfigurationFinish(readCompressedPacket(socket.getInputStream()));
                                writeLobbyProof(
                                        socket,
                                        new InstanceId("paper-instance-lobby-two"),
                                        new SessionId("session-lobby-scale-out"),
                                        LobbyCapabilitySeedProvisioner.offlineModeSubjectId("FulcrumBotFour").value(),
                                        "Fulcrum Bot Four",
                                        "[Admin] Fulcrum Bot Four: fulcrum-proof-chat");
                            }
                            case LOGIN_DENIED -> {
                                verifyHandshake(readPacket(socket.getInputStream()), 767, 2);
                                assertEquals("BannedFulcrumBot", readLoginStart(readPacket(socket.getInputStream())));
                                writeLoginDisconnect(socket, "{\"text\":\"Banned by Fulcrum\"}");
                            }
                        }
                    }
                }
                return null;
            });
            executor.shutdown();
            return new FakeMinecraftClusterServer(serverSocket, server);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            server.get(2, TimeUnit.SECONDS);
        }
    }

    private enum ExchangeKind {
        STATUS,
        LOGIN_ACCEPTED,
        LOBBY_PROOF_ACCEPTED,
        LOBBY_PROOF_BEFORE_PLAY,
        LOBBY_PROOF_SECOND_ACCEPTED,
        LOBBY_PROOF_SECOND_DIFFERENT_SESSION,
        LOBBY_PROOF_WRONG_SPAWN,
        LOGIN_DENIED_NO_ROUTE,
        LOBBY_PROOF_SCALE_OUT_ACCEPTED,
        LOGIN_DENIED
    }

    private static final class StatusExchange implements Callable<Void> {
        private final ServerSocket serverSocket;

        private StatusExchange(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        @Override
        public Void call() throws Exception {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(2_000);
                verifyHandshake(readPacket(socket.getInputStream()), 0, 1);
                verifyStatusRequest(readPacket(socket.getInputStream()));
                writeStatusResponse(socket);
            }
            return null;
        }
    }

    private static void verifyHandshake(byte[] packet, int protocolVersion, int nextState) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
        assertEquals(protocolVersion, MinecraftStatusClient.readVarInt(input));
        assertEquals("127.0.0.1", readString(input));
        assertTrue(readUnsignedShort(input) > 0);
        assertEquals(nextState, MinecraftStatusClient.readVarInt(input));
    }

    private static void verifyStatusRequest(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
    }

    private static void writeStatusResponse(Socket socket) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(body::write, 0);
        writeString(body, """
                {"version":{"name":"Fulcrum Test Velocity","protocol":767},"players":{"max":100,"online":2},"description":{"text":"Fulcrum lobby"}}
                """.trim());
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(frame::write, body.size());
        frame.writeBytes(body.toByteArray());
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    private static String readLoginStart(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(0, MinecraftStatusClient.readVarInt(input));
        String username = readString(input);
        assertEquals(16, input.readNBytes(16).length);
        return username;
    }

    private static void writeCompressionThenLoginSuccess(Socket socket, String username) throws IOException {
        ByteArrayOutputStream compression = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(compression::write, 3);
        MinecraftStatusClient.writeVarInt(compression::write, 256);
        writePacket(socket, compression.toByteArray());

        ByteArrayOutputStream success = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(success::write, 2);
        success.write(new byte[16]);
        writeString(success, username);
        MinecraftStatusClient.writeVarInt(success::write, 0);
        writeCompressedPacket(socket, success.toByteArray());
    }

    private static void writeConfigurationFinish(Socket socket) throws IOException {
        ByteArrayOutputStream finish = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(finish::write, 3);
        writeCompressedPacket(socket, finish.toByteArray());
    }

    private static void verifyConfigurationFinish(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        assertEquals(3, MinecraftStatusClient.readVarInt(input));
    }

    private static void writeLobbyProof(
            Socket socket,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                new InstanceId("paper-instance-lobby-one"),
                new SessionId("session-lobby-shared"),
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat) throws IOException {
        writeLobbyProof(
                socket,
                instanceId,
                sessionId,
                subjectUuid,
                displayName,
                decoratedChat,
                0,
                64,
                0,
                0.5D,
                65.0D,
                0.5D,
                0.0F,
                0.0F);
    }

    private static void writeLobbyProof(
            Socket socket,
            InstanceId instanceId,
            SessionId sessionId,
            UUID subjectUuid,
            String displayName,
            String decoratedChat,
            int bedrockBlockX,
            int bedrockBlockY,
            int bedrockBlockZ,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch) throws IOException {
        PaperLobbyProofMessage proof = new PaperLobbyProofMessage(
                instanceId,
                sessionId,
                new SubjectId(subjectUuid),
                "world",
                bedrockBlockX,
                bedrockBlockY,
                bedrockBlockZ,
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch,
                displayName,
                Optional.of("Admin"),
                decoratedChat);
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(packet::write, 24);
        writeString(packet, PaperLobbyProofMessage.CHANNEL);
        packet.writeBytes(proof.encode());
        writeCompressedPacket(socket, packet.toByteArray());
    }

    private static void writeLoginDisconnect(Socket socket, String reason) throws IOException {
        ByteArrayOutputStream disconnect = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(disconnect::write, 0);
        writeString(disconnect, reason);
        writePacket(socket, disconnect.toByteArray());
    }

    private static void writePacket(Socket socket, byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(frame::write, body.length);
        frame.writeBytes(body);
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    private static void writeCompressedPacket(Socket socket, byte[] body) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        MinecraftStatusClient.writeVarInt(payload::write, 0);
        payload.writeBytes(body);
        writePacket(socket, payload.toByteArray());
    }

    private static byte[] readPacket(InputStream input) throws IOException {
        int length = MinecraftStatusClient.readVarInt(input);
        byte[] packet = input.readNBytes(length);
        if (packet.length != length) {
            throw new EOFException("Expected " + length + " bytes, got " + packet.length);
        }
        return packet;
    }

    private static byte[] readCompressedPacket(InputStream input) throws IOException {
        ByteArrayInputStream packet = new ByteArrayInputStream(readPacket(input));
        assertEquals(0, MinecraftStatusClient.readVarInt(packet));
        return packet.readAllBytes();
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MinecraftStatusClient.writeVarInt(output::write, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(InputStream input) throws IOException {
        int length = MinecraftStatusClient.readVarInt(input);
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        if (high < 0 || low < 0) {
            throw new EOFException("Expected unsigned short");
        }
        return (high << 8) | low;
    }
}
