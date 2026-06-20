package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class LobbyClusterE2eVerifier {
    private static final double POSITION_TOLERANCE = 0.001D;
    private static final float ROTATION_TOLERANCE = 0.001F;
    private static final String DEFAULT_ENDPOINT_HOST = "127.0.0.1";

    private LobbyClusterE2eVerifier() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config.verifyScaleOut()) {
            throw new IllegalArgumentException(
                    "Scale-out verification is outside the bare Phase 0 lobby verifier; set --verify-scale-out=false");
        }
        if (config.deniedLoginUsername().isPresent()) {
            throw new IllegalArgumentException(
                    "Denied-login verification requires an external login-gate domain; omit --denied-login-username");
        }

        MinecraftStatusClient client = new MinecraftStatusClient();
        InetSocketAddress endpoint = new InetSocketAddress(config.effectiveEndpointHost(), config.endpointPort());
        MinecraftStatusClient.MinecraftStatusSnapshot status = waitForStatus(client, endpoint, config);
        int protocolVersion = config.protocolVersion() == 0 ? status.protocolVersion() : config.protocolVersion();
        if (config.protocolVersion() != 0 && status.protocolVersion() != config.protocolVersion()) {
            throw new IOException("Velocity status protocol " + status.protocolVersion()
                    + " did not match configured protocol " + config.protocolVersion());
        }

        PaperLobbyProofMessage primary = waitForLobbyProof(
                client,
                endpoint,
                protocolVersion,
                config.loginUsername(),
                config.timeout());
        PaperLobbyProofMessage second = waitForLobbyProof(
                client,
                endpoint,
                protocolVersion,
                config.secondLoginUsername(),
                config.timeout());

        verifyLobbyProof("primary accepted login", config, primary, config.expectedDisplayName(),
                config.expectedDecoratedChatContains());
        verifyLobbyProof("second accepted login", config, second, config.expectedSecondDisplayName(),
                config.expectedSecondDecoratedChatContains());
        verifySameSharedShard(primary, second);

        System.out.println("lobbyClusterE2e=bare");
        System.out.println("endpointHost=" + config.effectiveEndpointHost());
        System.out.println("endpointPort=" + config.endpointPort());
        System.out.println("statusProtocol=" + status.protocolVersion());
        System.out.println("primarySession=" + primary.sessionId().value());
        System.out.println("secondSession=" + second.sessionId().value());
        System.out.println("sharedShardSession=" + primary.sessionId().value());
    }

    static MinecraftStatusClient.MinecraftStatusSnapshot waitForStatus(
            MinecraftStatusClient client,
            InetSocketAddress endpoint,
            Config config) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + config.endpointReadyTimeout().toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.status(endpoint, config.protocolVersion(), timeoutSlice(deadline));
            } catch (IOException exception) {
                lastFailure = exception;
                Thread.sleep(250);
            }
        }
        throw new IOException("Timed out waiting for Minecraft status from " + endpoint, lastFailure);
    }

    static PaperLobbyProofMessage waitForLobbyProof(
            MinecraftStatusClient client,
            InetSocketAddress endpoint,
            int protocolVersion,
            String username,
            Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        MinecraftStatusClient.LobbyProofProbeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.lobbyProof(
                        endpoint,
                        protocolVersion,
                        username,
                        lobbyProofAttemptTimeout(deadline));
            } catch (MinecraftStatusClient.LobbyProofProbeException exception) {
                lastFailure = exception;
                System.err.println("Lobby proof probe for " + username
                        + " failed with " + exception.failure()
                        + "; retrying until " + timeout + " expires");
                Thread.sleep(500L);
            }
        }
        throw new IOException("Timed out waiting for lobby proof for " + username + " from " + endpoint, lastFailure);
    }

    static void verifyLobbyProof(
            String label,
            Config config,
            PaperLobbyProofMessage proof,
            String expectedDisplayName,
            String expectedDecoratedChatContains) throws IOException {
        if (!PaperLobbyProofMessage.SPAWN_BLOCK.equals(config.expectedSpawnBlock())) {
            throw new IOException(label + " expected unsupported spawn block " + config.expectedSpawnBlock()
                    + "; bare verifier only supports " + PaperLobbyProofMessage.SPAWN_BLOCK);
        }
        requireEquals(label, "spawnWorld", config.expectedSpawnWorld(), proof.spawnWorld());
        requireEquals(label, "resolvedManifestId", config.expectedResolvedManifestId(), proof.resolvedManifestId().value());
        requireEquals(label, "traceId", config.expectedTraceId(), proof.traceId());
        requireEquals(label, "bedrockBlockX", config.expectedBedrockBlockX(), proof.bedrockBlockX());
        requireEquals(label, "bedrockBlockY", config.expectedBedrockBlockY(), proof.bedrockBlockY());
        requireEquals(label, "bedrockBlockZ", config.expectedBedrockBlockZ(), proof.bedrockBlockZ());
        requireClose(label, "playerX", config.expectedPlayerX(), proof.playerX());
        requireClose(label, "playerY", config.expectedPlayerY(), proof.playerY());
        requireClose(label, "playerZ", config.expectedPlayerZ(), proof.playerZ());
        requireClose(label, "playerYaw", config.expectedPlayerYaw(), proof.playerYaw());
        requireClose(label, "playerPitch", config.expectedPlayerPitch(), proof.playerPitch());
        requireEquals(label, "displayName", expectedDisplayName, proof.displayName());
        if (!proof.decoratedChat().contains(expectedDecoratedChatContains)) {
            throw new IOException(label + " decoratedChat did not contain " + expectedDecoratedChatContains
                    + ": " + proof.decoratedChat());
        }
    }

    static void verifySameSharedShard(PaperLobbyProofMessage primary, PaperLobbyProofMessage second) throws IOException {
        requireEquals("shared shard", "instanceId", primary.instanceId().value(), second.instanceId().value());
        requireEquals("shared shard", "sessionId", primary.sessionId().value(), second.sessionId().value());
        requireEquals("shared shard", "slotId", primary.slotId().value(), second.slotId().value());
        requireEquals(
                "shared shard",
                "resolvedManifestId",
                primary.resolvedManifestId().value(),
                second.resolvedManifestId().value());
        if (primary.subjectId().equals(second.subjectId())) {
            throw new IOException("shared shard proofs must be for distinct Subjects: " + primary.subjectId().value());
        }
    }

    private static Duration timeoutSlice(long deadline) {
        long remainingMillis = Math.max(1L, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
        return Duration.ofMillis(Math.min(5_000L, remainingMillis));
    }

    private static Duration lobbyProofAttemptTimeout(long deadline) {
        long remainingMillis = Math.max(1L, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
        return Duration.ofMillis(Math.min(60_000L, remainingMillis));
    }

    private static void requireEquals(String label, String field, Object expected, Object actual) throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException(label + " " + field + " expected " + expected + " but was " + actual);
        }
    }

    private static void requireClose(String label, String field, double expected, double actual) throws IOException {
        if (Math.abs(expected - actual) > POSITION_TOLERANCE) {
            throw new IOException(label + " " + field + " expected " + expected + " but was " + actual);
        }
    }

    private static void requireClose(String label, String field, float expected, float actual) throws IOException {
        if (Math.abs(expected - actual) > ROTATION_TOLERANCE) {
            throw new IOException(label + " " + field + " expected " + expected + " but was " + actual);
        }
    }

    record Config(
            Optional<String> endpointHost,
            int endpointPort,
            int protocolVersion,
            String loginUsername,
            String secondLoginUsername,
            String expectedSpawnBlock,
            String expectedSpawnWorld,
            String expectedResolvedManifestId,
            String expectedTraceId,
            int expectedBedrockBlockX,
            int expectedBedrockBlockY,
            int expectedBedrockBlockZ,
            double expectedPlayerX,
            double expectedPlayerY,
            double expectedPlayerZ,
            float expectedPlayerYaw,
            float expectedPlayerPitch,
            String expectedDisplayName,
            String expectedDecoratedChatContains,
            String expectedSecondDisplayName,
            String expectedSecondDecoratedChatContains,
            boolean verifyScaleOut,
            Optional<String> deniedLoginUsername,
            Duration timeout,
            Duration endpointReadyTimeout,
            Map<String, String> ignoredArgs) {
        Config {
            endpointHost = endpointHost == null ? Optional.empty() : endpointHost
                    .map(value -> requireNonBlank(value, "endpoint host"));
            if (endpointPort < 1 || endpointPort > 65_535) {
                throw new IllegalArgumentException("endpoint port must be between 1 and 65535");
            }
            if (protocolVersion < 0) {
                throw new IllegalArgumentException("protocol version must be zero or positive");
            }
            loginUsername = requireNonBlank(loginUsername, "login username");
            secondLoginUsername = requireNonBlank(secondLoginUsername, "second login username");
            expectedSpawnBlock = requireNonBlank(expectedSpawnBlock, "expected spawn block");
            expectedSpawnWorld = requireNonBlank(expectedSpawnWorld, "expected spawn world");
            expectedResolvedManifestId = requireNonBlank(expectedResolvedManifestId, "expected resolved manifest id");
            expectedTraceId = requireNonBlank(expectedTraceId, "expected trace id");
            expectedDisplayName = requireNonBlank(expectedDisplayName, "expected display name");
            expectedDecoratedChatContains = requireNonBlank(
                    expectedDecoratedChatContains,
                    "expected decorated chat contains");
            expectedSecondDisplayName = requireNonBlank(expectedSecondDisplayName, "expected second display name");
            expectedSecondDecoratedChatContains = requireNonBlank(
                    expectedSecondDecoratedChatContains,
                    "expected second decorated chat contains");
            deniedLoginUsername = deniedLoginUsername == null ? Optional.empty() : deniedLoginUsername
                    .map(value -> requireNonBlank(value, "denied login username"));
            timeout = requirePositive(timeout == null ? Duration.ofMinutes(5) : timeout, "timeout");
            endpointReadyTimeout = requirePositive(
                    endpointReadyTimeout == null ? Duration.ofMinutes(2) : endpointReadyTimeout,
                    "endpoint ready timeout");
            ignoredArgs = Map.copyOf(ignoredArgs == null ? Map.of() : ignoredArgs);
        }

        String effectiveEndpointHost() {
            return endpointHost.orElse(DEFAULT_ENDPOINT_HOST);
        }

        static Config parse(String[] args) {
            Optional<String> endpointHost = Optional.empty();
            int endpointPort = 25565;
            int protocolVersion = 0;
            String loginUsername = "FulcrumBotOne";
            String secondLoginUsername = "FulcrumBotTwo";
            String expectedSpawnBlock = PaperLobbyProofMessage.SPAWN_BLOCK;
            String expectedSpawnWorld = "world";
            String expectedResolvedManifestId = "manifest-lobby-bedrock-v1";
            String expectedTraceId = "trace-paper-session-lobby-shared";
            int expectedBedrockBlockX = 0;
            int expectedBedrockBlockY = 64;
            int expectedBedrockBlockZ = 0;
            double expectedPlayerX = 0.5D;
            double expectedPlayerY = 65.0D;
            double expectedPlayerZ = 0.5D;
            float expectedPlayerYaw = 0.0F;
            float expectedPlayerPitch = 0.0F;
            String expectedDisplayName = "Fulcrum Bot One";
            String expectedDecoratedChatContains = "FulcrumBotOne: fulcrum-proof-chat";
            String expectedSecondDisplayName = "Fulcrum Bot Two";
            String expectedSecondDecoratedChatContains = "FulcrumBotTwo: fulcrum-proof-chat";
            boolean verifyScaleOut = false;
            Optional<String> deniedLoginUsername = Optional.empty();
            Duration timeout = Duration.ofMinutes(5);
            Duration endpointReadyTimeout = Duration.ofMinutes(2);
            Map<String, String> ignored = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Verifier arguments must use --name=value syntax: " + arg);
                }
                String key = arg.substring(2, arg.indexOf('='));
                String value = arg.substring(arg.indexOf('=') + 1);
                switch (key) {
                    case "endpoint-host" -> endpointHost = value.isBlank() ? Optional.empty() : Optional.of(value);
                    case "endpoint-port" -> endpointPort = Integer.parseInt(value);
                    case "protocol-version" -> protocolVersion = Integer.parseInt(value);
                    case "login-username" -> loginUsername = value;
                    case "second-login-username" -> secondLoginUsername = value;
                    case "expected-lobby-spawn-block" -> expectedSpawnBlock = value;
                    case "expected-lobby-spawn-world" -> expectedSpawnWorld = value;
                    case "expected-lobby-resolved-manifest-id" -> expectedResolvedManifestId = value;
                    case "expected-lobby-trace-id" -> expectedTraceId = value;
                    case "expected-lobby-bedrock-block-x" -> expectedBedrockBlockX = Integer.parseInt(value);
                    case "expected-lobby-bedrock-block-y" -> expectedBedrockBlockY = Integer.parseInt(value);
                    case "expected-lobby-bedrock-block-z" -> expectedBedrockBlockZ = Integer.parseInt(value);
                    case "expected-lobby-player-x" -> expectedPlayerX = Double.parseDouble(value);
                    case "expected-lobby-player-y" -> expectedPlayerY = Double.parseDouble(value);
                    case "expected-lobby-player-z" -> expectedPlayerZ = Double.parseDouble(value);
                    case "expected-lobby-player-yaw" -> expectedPlayerYaw = Float.parseFloat(value);
                    case "expected-lobby-player-pitch" -> expectedPlayerPitch = Float.parseFloat(value);
                    case "expected-lobby-display-name" -> expectedDisplayName = value;
                    case "expected-lobby-decorated-chat-contains" -> expectedDecoratedChatContains = value;
                    case "expected-second-lobby-display-name" -> expectedSecondDisplayName = value;
                    case "expected-second-lobby-decorated-chat-contains" -> expectedSecondDecoratedChatContains = value;
                    case "verify-scale-out" -> verifyScaleOut = Boolean.parseBoolean(value);
                    case "denied-login-username" -> deniedLoginUsername = value.isBlank()
                            ? Optional.empty()
                            : Optional.of(value);
                    case "timeout" -> timeout = Duration.parse(value);
                    case "endpoint-ready-timeout" -> endpointReadyTimeout = Duration.parse(value);
                    default -> ignored.put(key, value);
                }
            }
            return new Config(
                    endpointHost,
                    endpointPort,
                    protocolVersion,
                    loginUsername,
                    secondLoginUsername,
                    expectedSpawnBlock,
                    expectedSpawnWorld,
                    expectedResolvedManifestId,
                    expectedTraceId,
                    expectedBedrockBlockX,
                    expectedBedrockBlockY,
                    expectedBedrockBlockZ,
                    expectedPlayerX,
                    expectedPlayerY,
                    expectedPlayerZ,
                    expectedPlayerYaw,
                    expectedPlayerPitch,
                    expectedDisplayName,
                    expectedDecoratedChatContains,
                    expectedSecondDisplayName,
                    expectedSecondDecoratedChatContains,
                    verifyScaleOut,
                    deniedLoginUsername,
                    timeout,
                    endpointReadyTimeout,
                    ignored);
        }

        private static String requireNonBlank(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return value;
        }

        private static Duration requirePositive(Duration value, String label) {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(label + " must be positive");
            }
            return value;
        }
    }
}
