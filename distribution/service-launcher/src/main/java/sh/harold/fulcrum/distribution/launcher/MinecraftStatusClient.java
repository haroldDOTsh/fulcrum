package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

final class MinecraftStatusClient {
    private static final int STATUS_STATE = 1;
    private static final int LOGIN_STATE = 2;
    private static final int HANDSHAKE_PACKET_ID = 0;
    private static final int STATUS_REQUEST_PACKET_ID = 0;
    private static final int STATUS_RESPONSE_PACKET_ID = 0;
    private static final int LOGIN_START_PACKET_ID = 0;
    private static final int LOGIN_DISCONNECT_PACKET_ID = 0;
    private static final int LOGIN_ENCRYPTION_REQUEST_PACKET_ID = 1;
    private static final int LOGIN_SUCCESS_PACKET_ID = 2;
    private static final int LOGIN_SET_COMPRESSION_PACKET_ID = 3;
    private static final int LOGIN_PLUGIN_REQUEST_PACKET_ID = 4;
    private static final int LOGIN_PLUGIN_RESPONSE_PACKET_ID = 2;
    private static final int CONFIGURATION_FINISH_CLIENTBOUND_PACKET_ID = 3;
    private static final int CONFIGURATION_FINISH_SERVERBOUND_PACKET_ID = 3;
    private static final int CONFIGURATION_KEEP_ALIVE_CLIENTBOUND_PACKET_ID = 4;
    private static final int CONFIGURATION_KEEP_ALIVE_SERVERBOUND_PACKET_ID = 4;
    private static final int PLAY_CUSTOM_PAYLOAD_PACKET_ID = 24;
    private static final int LOGIN_PACKET_LIMIT = 16;
    private static final int MAX_STATUS_PACKET_BYTES = 2 * 1024 * 1024;
    private static final Pattern VERSION_NAME =
            Pattern.compile("\"version\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_PROTOCOL =
            Pattern.compile("\"version\"\\s*:\\s*\\{[^}]*\"protocol\"\\s*:\\s*(\\d+)");
    private static final Pattern PLAYERS_BLOCK =
            Pattern.compile("\"players\"\\s*:\\s*\\{([^}]*)}");
    private static final Pattern MAX_PLAYERS = Pattern.compile("\"max\"\\s*:\\s*(\\d+)");
    private static final Pattern ONLINE_PLAYERS = Pattern.compile("\"online\"\\s*:\\s*(\\d+)");

    MinecraftStatusSnapshot status(InetSocketAddress endpoint, int protocolVersion, Duration timeout)
            throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            writePacket(socket, handshakePacket(endpoint, protocolVersion));
            writePacket(socket, statusRequestPacket());
            return decodeStatusResponse(readPacket(socket.getInputStream()));
        }
    }

    LoginAttemptResult login(InetSocketAddress endpoint, int protocolVersion, String username, Duration timeout)
            throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        String normalizedUsername = requireNonBlank(username, "username");
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            writePacket(socket, handshakePacket(endpoint, protocolVersion, LOGIN_STATE));
            writePacket(socket, loginStartPacket(normalizedUsername));

            boolean compressed = false;
            for (int packets = 0; packets < LOGIN_PACKET_LIMIT; packets++) {
                byte[] payload = readPacket(socket.getInputStream());
                if (compressed) {
                    payload = decompress(payload);
                }
                ByteArrayInputStream input = new ByteArrayInputStream(payload);
                int packetId = readVarInt(input);
                if (packetId == LOGIN_SUCCESS_PACKET_ID) {
                    return LoginAttemptResult.accepted(normalizedUsername);
                }
                if (packetId == LOGIN_DISCONNECT_PACKET_ID) {
                    return LoginAttemptResult.denied(normalizedUsername, readString(input));
                }
                if (packetId == LOGIN_ENCRYPTION_REQUEST_PACKET_ID) {
                    throw new IOException("Minecraft login requested encryption; Velocity should run offline-mode for this verifier");
                }
                if (packetId == LOGIN_SET_COMPRESSION_PACKET_ID) {
                    readVarInt(input);
                    compressed = true;
                    continue;
                }
                if (packetId == LOGIN_PLUGIN_REQUEST_PACKET_ID) {
                    int messageId = readVarInt(input);
                    writePacket(socket, loginPluginResponsePacket(messageId), compressed);
                }
            }
            throw new IOException("Minecraft login did not reach success or disconnect after "
                    + LOGIN_PACKET_LIMIT + " packets");
        }
    }

    PaperLobbyProofMessage lobbyProof(
            InetSocketAddress endpoint,
            int protocolVersion,
            String username,
            Duration timeout) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        String normalizedUsername = requireNonBlank(username, "username");
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, remainingTimeoutMillis(deadline));
            socket.setSoTimeout(remainingTimeoutMillis(deadline));
            writePacket(socket, handshakePacket(endpoint, protocolVersion, LOGIN_STATE));
            writePacket(socket, loginStartPacket(normalizedUsername));

            boolean compressed = false;
            ProtocolState state = ProtocolState.LOGIN;
            while (true) {
                socket.setSoTimeout(remainingTimeoutMillis(deadline));
                byte[] payload = readPacket(socket.getInputStream());
                if (compressed) {
                    payload = decompress(payload);
                }
                Optional<PaperLobbyProofMessage> proof = lobbyProof(payload);
                if (proof.isPresent()) {
                    if (state != ProtocolState.PLAY) {
                        throw new IOException(
                                "Received lobby proof before Minecraft client reached play state for "
                                        + normalizedUsername);
                    }
                    return proof.orElseThrow();
                }

                ByteArrayInputStream input = new ByteArrayInputStream(payload);
                int packetId = readVarInt(input);
                if (state == ProtocolState.LOGIN) {
                    if (packetId == LOGIN_SUCCESS_PACKET_ID) {
                        state = ProtocolState.CONFIGURATION;
                    } else if (packetId == LOGIN_DISCONNECT_PACKET_ID) {
                        throw new IOException("Expected accepted login for " + normalizedUsername
                                + ", got denial " + readString(input));
                    } else if (packetId == LOGIN_ENCRYPTION_REQUEST_PACKET_ID) {
                        throw new IOException(
                                "Minecraft login requested encryption; Velocity should run offline-mode for this verifier");
                    } else if (packetId == LOGIN_SET_COMPRESSION_PACKET_ID) {
                        readVarInt(input);
                        compressed = true;
                    } else if (packetId == LOGIN_PLUGIN_REQUEST_PACKET_ID) {
                        int messageId = readVarInt(input);
                        writePacket(socket, loginPluginResponsePacket(messageId), compressed);
                    }
                } else if (state == ProtocolState.CONFIGURATION) {
                    if (packetId == CONFIGURATION_FINISH_CLIENTBOUND_PACKET_ID) {
                        writePacket(socket, configurationFinishPacket(), compressed);
                        state = ProtocolState.PLAY;
                    } else if (packetId == CONFIGURATION_KEEP_ALIVE_CLIENTBOUND_PACKET_ID) {
                        writePacket(socket, configurationKeepAliveResponsePacket(input), compressed);
                    }
                }
            }
        } catch (SocketTimeoutException exception) {
            throw new IOException("Timed out waiting for lobby proof from " + endpoint, exception);
        }
    }

    private static byte[] handshakePacket(InetSocketAddress endpoint, int protocolVersion) {
        return handshakePacket(endpoint, protocolVersion, STATUS_STATE);
    }

    private static byte[] handshakePacket(InetSocketAddress endpoint, int protocolVersion, int nextState) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, HANDSHAKE_PACKET_ID);
        writeVarInt(packet::write, protocolVersion);
        writeString(packet, endpoint.getHostString());
        writeUnsignedShort(packet, endpoint.getPort());
        writeVarInt(packet::write, nextState);
        return packet.toByteArray();
    }

    private static byte[] statusRequestPacket() {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, STATUS_REQUEST_PACKET_ID);
        return packet.toByteArray();
    }

    private static byte[] loginStartPacket(String username) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, LOGIN_START_PACKET_ID);
        writeString(packet, username);
        writeUuid(packet, offlineUuid(username));
        return packet.toByteArray();
    }

    private static byte[] loginPluginResponsePacket(int messageId) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, LOGIN_PLUGIN_RESPONSE_PACKET_ID);
        writeVarInt(packet::write, messageId);
        packet.write(0);
        return packet.toByteArray();
    }

    private static byte[] configurationFinishPacket() {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, CONFIGURATION_FINISH_SERVERBOUND_PACKET_ID);
        return packet.toByteArray();
    }

    private static byte[] configurationKeepAliveResponsePacket(InputStream input) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet::write, CONFIGURATION_KEEP_ALIVE_SERVERBOUND_PACKET_ID);
        byte[] keepAliveId = input.readNBytes(Long.BYTES);
        if (keepAliveId.length != Long.BYTES) {
            throw new EOFException("Expected configuration keep-alive id");
        }
        packet.writeBytes(keepAliveId);
        return packet.toByteArray();
    }

    private static void writePacket(Socket socket, byte[] packet) throws IOException {
        writePacket(socket, packet, false);
    }

    private static void writePacket(Socket socket, byte[] packet, boolean compressed) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        byte[] payload = packet;
        if (compressed) {
            ByteArrayOutputStream compressedPayload = new ByteArrayOutputStream();
            writeVarInt(compressedPayload::write, 0);
            compressedPayload.writeBytes(packet);
            payload = compressedPayload.toByteArray();
        }
        writeVarInt(framed::write, payload.length);
        framed.writeBytes(payload);
        socket.getOutputStream().write(framed.toByteArray());
        socket.getOutputStream().flush();
    }

    private static byte[] readPacket(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_STATUS_PACKET_BYTES) {
            throw new IOException("Minecraft status packet length is out of range: " + length);
        }
        byte[] packet = input.readNBytes(length);
        if (packet.length != length) {
            throw new EOFException("Expected " + length + " Minecraft status packet bytes, got " + packet.length);
        }
        return packet;
    }

    private static byte[] decompress(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        int uncompressedLength = readVarInt(input);
        if (uncompressedLength == 0) {
            return input.readAllBytes();
        }
        byte[] decompressed = new InflaterInputStream(input).readAllBytes();
        if (decompressed.length != uncompressedLength) {
            throw new IOException("Expected " + uncompressedLength
                    + " decompressed Minecraft packet bytes, got " + decompressed.length);
        }
        return decompressed;
    }

    private static Optional<PaperLobbyProofMessage> lobbyProof(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        int packetId = readVarInt(input);
        if (packetId != PLAY_CUSTOM_PAYLOAD_PACKET_ID) {
            return Optional.empty();
        }
        String channel = readString(input);
        if (!PaperLobbyProofMessage.CHANNEL.equals(channel)) {
            return Optional.empty();
        }
        String payload = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        if (!payload.startsWith(PaperLobbyProofMessage.MARKER)) {
            throw new IOException("Fulcrum lobby proof custom payload is missing marker "
                    + PaperLobbyProofMessage.MARKER);
        }
        return Optional.of(PaperLobbyProofMessage.parse(payload));
    }

    private static MinecraftStatusSnapshot decodeStatusResponse(byte[] packet) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(packet);
        int packetId = readVarInt(input);
        if (packetId != STATUS_RESPONSE_PACKET_ID) {
            throw new IOException("Expected Minecraft status response packet id 0, got " + packetId);
        }
        String payload = readString(input);
        return MinecraftStatusSnapshot.parse(payload);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output::write, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readString(InputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_STATUS_PACKET_BYTES) {
            throw new IOException("Minecraft status string length is out of range: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Expected " + length + " Minecraft status string bytes, got " + bytes.length);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
        if (value < 0 || value > 65_535) {
            throw new IllegalArgumentException("port out of range: " + value);
        }
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeUuid(ByteArrayOutputStream output, UUID uuid) {
        writeLong(output, uuid.getMostSignificantBits());
        writeLong(output, uuid.getLeastSignificantBits());
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    static void writeVarInt(IntConsumer output, int value) {
        int remaining = value;
        do {
            int temp = remaining & 0b0111_1111;
            remaining >>>= 7;
            if (remaining != 0) {
                temp |= 0b1000_0000;
            }
            output.accept(temp);
        } while (remaining != 0);
    }

    static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 35) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("Unexpected end of stream while reading Minecraft VarInt");
            }
            value |= (current & 0b0111_1111) << position;
            if ((current & 0b1000_0000) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("Minecraft VarInt is too long");
    }

    private static int remainingTimeoutMillis(long deadlineNanos) throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Minecraft verifier deadline expired");
        }
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
    }

    private enum ProtocolState {
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    record MinecraftStatusSnapshot(
            String rawJson,
            String versionName,
            int protocolVersion,
            int onlinePlayers,
            int maxPlayers) {
        MinecraftStatusSnapshot {
            rawJson = requireNonBlank(rawJson, "rawJson");
            versionName = requireNonBlank(versionName, "versionName");
            if (protocolVersion < 0) {
                throw new IllegalArgumentException("protocolVersion must be non-negative");
            }
            if (onlinePlayers < 0) {
                throw new IllegalArgumentException("onlinePlayers must be non-negative");
            }
            if (maxPlayers < 0) {
                throw new IllegalArgumentException("maxPlayers must be non-negative");
            }
        }

        static MinecraftStatusSnapshot parse(String rawJson) throws IOException {
            String versionName = match(VERSION_NAME, rawJson, "version.name");
            int protocolVersion = Integer.parseInt(match(VERSION_PROTOCOL, rawJson, "version.protocol"));
            String players = match(PLAYERS_BLOCK, rawJson, "players");
            int onlinePlayers = Integer.parseInt(match(ONLINE_PLAYERS, players, "players.online"));
            int maxPlayers = Integer.parseInt(match(MAX_PLAYERS, players, "players.max"));
            return new MinecraftStatusSnapshot(rawJson, versionName, protocolVersion, onlinePlayers, maxPlayers);
        }

        private static String match(Pattern pattern, String value, String label) throws IOException {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.find()) {
                throw new IOException("Minecraft status response did not include " + label + ": " + value);
            }
            return matcher.group(1);
        }
    }

    record LoginAttemptResult(
            String username,
            boolean accepted,
            Optional<String> denialReason) {
        LoginAttemptResult {
            username = requireNonBlank(username, "username");
            denialReason = Objects.requireNonNull(denialReason, "denialReason")
                    .filter(value -> !value.isBlank());
            if (!accepted && denialReason.isEmpty()) {
                throw new IllegalArgumentException("denied login result requires a reason");
            }
        }

        static LoginAttemptResult accepted(String username) {
            return new LoginAttemptResult(username, true, Optional.empty());
        }

        static LoginAttemptResult denied(String username, String reason) {
            return new LoginAttemptResult(username, false, Optional.of(reason));
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
