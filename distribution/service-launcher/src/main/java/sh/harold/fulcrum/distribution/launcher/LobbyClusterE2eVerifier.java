package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.MinecraftStatusSnapshot;
import sh.harold.fulcrum.distribution.launcher.MinecraftStatusClient.LoginAttemptResult;
import sh.harold.fulcrum.host.paper.PaperLobbyProofMessage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LobbyClusterE2eVerifier {
    private static final double POSITION_TOLERANCE = 0.001D;

    private LobbyClusterE2eVerifier() {
    }

    public static void main(String[] args) throws Exception {
        VerificationConfig config = VerificationConfig.parse(args);
        ResolvedMinecraftEndpoint endpoint = resolveEndpoint(config);
        MinecraftStatusClient client = new MinecraftStatusClient();
        MinecraftStatusSnapshot status = client.status(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                config.protocolVersion(),
                config.timeout());
        int loginProtocolVersion = config.protocolVersion() == 0
                ? status.protocolVersion()
                : config.protocolVersion();
        PaperLobbyProofMessage lobbyProof = client.lobbyProof(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                loginProtocolVersion,
                config.loginUsername(),
                config.timeout());
        verifyLobbyProof(
                "primary accepted login",
                config,
                lobbyProof,
                config.loginUsername(),
                config.expectedDisplayName(),
                config.expectedRankLabel(),
                config.expectedDecoratedChatContains());
        PaperLobbyProofMessage secondLobbyProof = client.lobbyProof(
                new InetSocketAddress(endpoint.host(), endpoint.port()),
                loginProtocolVersion,
                config.secondLoginUsername(),
                config.timeout());
        verifyLobbyProof(
                "second accepted login",
                config,
                secondLobbyProof,
                config.secondLoginUsername(),
                config.expectedSecondDisplayName(),
                config.expectedSecondRankLabel(),
                config.expectedSecondDecoratedChatContains());
        verifySameSharedShard(lobbyProof, secondLobbyProof);
        Optional<ScaleOutProof> scaleOutProof = Optional.empty();
        if (config.verifyScaleOut()) {
            scaleOutProof = Optional.of(verifyScaleOut(config, endpoint, client, loginProtocolVersion, lobbyProof));
        }
        Optional<Integer> allocatedReplicas = verifyAgonesFleetState(config);
        Optional<LoginAttemptResult> deniedLogin = Optional.empty();
        if (config.deniedLoginUsername().isPresent()) {
            LoginAttemptResult result = client.login(
                    new InetSocketAddress(endpoint.host(), endpoint.port()),
                    loginProtocolVersion,
                    config.deniedLoginUsername().orElseThrow(),
                    config.timeout());
            if (result.accepted()) {
                throw new IOException("Expected denied login for " + config.deniedLoginUsername().orElseThrow()
                        + ", but Velocity accepted the login");
            }
            config.deniedLoginReasonContains().ifPresent(expected -> {
                String actual = result.denialReason().orElse("");
                if (!actual.contains(expected)) {
                    throw new IllegalStateException("Expected denied login reason to contain '" + expected
                            + "', got " + actual);
                }
            });
            deniedLogin = Optional.of(result);
        }
        System.out.printf(
                "Verified Minecraft status at %s:%d: version=%s protocol=%d online=%d max=%d%n",
                endpoint.host(),
                endpoint.port(),
                status.versionName(),
                status.protocolVersion(),
                status.onlinePlayers(),
                status.maxPlayers());
        System.out.printf(
                "Verified accepted Minecraft login and lobby proof for %s through %s:%d: displayName=%s rank=%s%n",
                config.loginUsername(),
                endpoint.host(),
                endpoint.port(),
                lobbyProof.displayName(),
                lobbyProof.rankLabel().orElse("<none>"));
        System.out.printf(
                "Verified second accepted Minecraft login joined same lobby Session for %s: instance=%s session=%s%n",
                config.secondLoginUsername(),
                secondLobbyProof.instanceId().value(),
                secondLobbyProof.sessionId().value());
        scaleOutProof.ifPresent(proof -> System.out.printf(
                "Verified full lobby scale-out: %s was denied to trigger allocation, then %s joined instance=%s session=%s%n",
                proof.triggerDeniedLogin().username(),
                config.scaleOutLoginUsername(),
                proof.acceptedLoginProof().instanceId().value(),
                proof.acceptedLoginProof().sessionId().value()));
        allocatedReplicas.ifPresent(replicas -> System.out.printf(
                "Verified Agones Fleet %s allocatedReplicas=%d%n",
                config.agonesFleetName(),
                replicas));
        deniedLogin.ifPresent(result -> System.out.printf(
                "Verified denied Minecraft login for %s through %s:%d: %s%n",
                result.username(),
                endpoint.host(),
                endpoint.port(),
                result.denialReason().orElse("<missing reason>")));
    }

    private static ScaleOutProof verifyScaleOut(
            VerificationConfig config,
            ResolvedMinecraftEndpoint endpoint,
            MinecraftStatusClient client,
            int protocolVersion,
            PaperLobbyProofMessage filledLobbyProof) throws IOException {
        InetSocketAddress socketAddress = new InetSocketAddress(endpoint.host(), endpoint.port());
        LoginAttemptResult trigger = client.login(
                socketAddress,
                protocolVersion,
                config.scaleOutTriggerLoginUsername(),
                config.timeout());
        if (trigger.accepted()) {
            throw new IOException("Expected full-lobby trigger login for " + config.scaleOutTriggerLoginUsername()
                    + " to be denied while allocation is requested, but Velocity accepted the login");
        }
        config.scaleOutTriggerDeniedReasonContains().ifPresent(expected -> {
            String actual = trigger.denialReason().orElse("");
            if (!actual.contains(expected)) {
                throw new IllegalStateException("Expected full-lobby trigger denial reason to contain '"
                        + expected + "', got " + actual);
            }
        });

        long deadline = System.nanoTime() + config.scaleOutTimeout().toNanos();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            try {
                PaperLobbyProofMessage scaleOutProof = client.lobbyProof(
                        socketAddress,
                        protocolVersion,
                        config.scaleOutLoginUsername(),
                        attemptTimeout(config));
                verifyLobbyProof(
                        "scale-out accepted login",
                        config,
                        scaleOutProof,
                        config.scaleOutLoginUsername(),
                        config.expectedScaleOutDisplayName(),
                        config.expectedScaleOutRankLabel(),
                        config.expectedScaleOutDecoratedChatContains());
                verifyDifferentSharedShard(filledLobbyProof, scaleOutProof);
                return new ScaleOutProof(trigger, scaleOutProof);
            } catch (IOException | IllegalStateException exception) {
                failures.add(exception.getMessage());
                sleepBeforeRetry(deadline);
            }
        }
        throw new IOException("Timed out waiting for scale-out login " + config.scaleOutLoginUsername()
                + " to reach a different lobby shard after trigger denial. Last failures: "
                + String.join(" | ", failures));
    }

    private static Duration attemptTimeout(VerificationConfig config) {
        return Duration.ofMillis(Math.max(500L, Math.min(config.timeout().toMillis(), 5_000L)));
    }

    private static void sleepBeforeRetry(long deadline) throws IOException {
        long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
        if (remainingMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(Math.min(500L, remainingMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for scale-out lobby allocation", exception);
        }
    }

    private static void verifyLobbyProof(
            String label,
            VerificationConfig config,
            PaperLobbyProofMessage proof,
            String username,
            String expectedDisplayName,
            String expectedRankLabel,
            String expectedDecoratedChatContains) {
        if (!PaperLobbyProofMessage.SPAWN_BLOCK.equals(config.expectedSpawnBlock())) {
            throw new IllegalStateException("Unsupported expected lobby spawn block "
                    + config.expectedSpawnBlock());
        }
        if (!config.expectedSpawnWorld().equals(proof.spawnWorld())) {
            throw new IllegalStateException("Expected " + label + " lobby spawnWorld "
                    + config.expectedSpawnWorld() + ", got " + proof.spawnWorld());
        }
        if (config.expectedBedrockBlockX() != proof.bedrockBlockX()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockX "
                    + config.expectedBedrockBlockX() + ", got " + proof.bedrockBlockX());
        }
        if (config.expectedBedrockBlockY() != proof.bedrockBlockY()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockY "
                    + config.expectedBedrockBlockY() + ", got " + proof.bedrockBlockY());
        }
        if (config.expectedBedrockBlockZ() != proof.bedrockBlockZ()) {
            throw new IllegalStateException("Expected " + label + " lobby bedrockBlockZ "
                    + config.expectedBedrockBlockZ() + ", got " + proof.bedrockBlockZ());
        }
        assertClose(label, "playerX", config.expectedPlayerX(), proof.playerX());
        assertClose(label, "playerY", config.expectedPlayerY(), proof.playerY());
        assertClose(label, "playerZ", config.expectedPlayerZ(), proof.playerZ());
        assertClose(label, "playerYaw", config.expectedPlayerYaw(), proof.playerYaw());
        assertClose(label, "playerPitch", config.expectedPlayerPitch(), proof.playerPitch());
        if (!LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username).equals(proof.subjectId())) {
            throw new IllegalStateException("Expected " + label + " SubjectId for " + username
                    + " to be " + LobbyCapabilitySeedProvisioner.offlineModeSubjectId(username).value()
                    + ", got " + proof.subjectId().value());
        }
        if (!expectedDisplayName.equals(proof.displayName())) {
            throw new IllegalStateException("Expected " + label + " lobby displayName " + expectedDisplayName
                    + ", got " + proof.displayName());
        }
        String rankLabel = proof.rankLabel().orElse("");
        if (!expectedRankLabel.equals(rankLabel)) {
            throw new IllegalStateException("Expected " + label + " lobby rankLabel " + expectedRankLabel
                    + ", got " + rankLabel);
        }
        if (!proof.decoratedChat().contains(expectedDecoratedChatContains)) {
            throw new IllegalStateException("Expected " + label + " lobby decoratedChat to contain '"
                    + expectedDecoratedChatContains + "', got " + proof.decoratedChat());
        }
    }

    private static void assertClose(String label, String field, double expected, double actual) {
        if (Math.abs(expected - actual) > POSITION_TOLERANCE) {
            throw new IllegalStateException("Expected " + label + " lobby " + field
                    + " " + expected + ", got " + actual);
        }
    }

    private static void verifySameSharedShard(
            PaperLobbyProofMessage first,
            PaperLobbyProofMessage second) {
        if (!first.sessionId().equals(second.sessionId())) {
            throw new IllegalStateException("Expected second accepted login to join Session "
                    + first.sessionId().value() + ", got " + second.sessionId().value());
        }
        if (!first.instanceId().equals(second.instanceId())) {
            throw new IllegalStateException("Expected second accepted login to join Paper Instance "
                    + first.instanceId().value() + ", got " + second.instanceId().value());
        }
    }

    private static void verifyDifferentSharedShard(
            PaperLobbyProofMessage filledLobbyProof,
            PaperLobbyProofMessage scaleOutProof) {
        if (filledLobbyProof.sessionId().equals(scaleOutProof.sessionId())) {
            throw new IllegalStateException("Expected scale-out accepted login to join a new Session, got "
                    + scaleOutProof.sessionId().value());
        }
        if (filledLobbyProof.instanceId().equals(scaleOutProof.instanceId())) {
            throw new IllegalStateException("Expected scale-out accepted login to join a new Paper Instance, got "
                    + scaleOutProof.instanceId().value());
        }
    }

    private static Optional<Integer> verifyAgonesFleetState(VerificationConfig config) throws IOException {
        if (!config.verifyAgonesFleetState()) {
            return Optional.empty();
        }
        Optional<String> allocatedReplicas = kubectl(config, "Agones Fleet allocated replicas",
                "get", "fleet", config.agonesFleetName(), "-o", "jsonpath={.status.allocatedReplicas}");
        String value = allocatedReplicas.orElseThrow(() -> new IOException("Agones Fleet "
                + config.namespace() + "/" + config.agonesFleetName()
                + " did not report status.allocatedReplicas"));
        int replicas = parseNonNegativeInteger(value, "Agones Fleet allocated replicas");
        if (replicas != config.expectedAgonesAllocatedReplicas()) {
            throw new IOException("Expected Agones Fleet " + config.namespace() + "/"
                    + config.agonesFleetName() + " allocatedReplicas="
                    + config.expectedAgonesAllocatedReplicas() + ", got " + replicas);
        }
        return Optional.of(replicas);
    }

    private static ResolvedMinecraftEndpoint resolveEndpoint(VerificationConfig config) throws IOException {
        if (config.endpointHost().isPresent()) {
            return new ResolvedMinecraftEndpoint(config.endpointHost().orElseThrow(), config.endpointPort());
        }
        Optional<String> loadBalancerIp = kubectl(config, "service load balancer IP",
                "get", "service", config.serviceName(), "-o", "jsonpath={.status.loadBalancer.ingress[0].ip}");
        if (loadBalancerIp.isPresent()) {
            return new ResolvedMinecraftEndpoint(loadBalancerIp.orElseThrow(), config.endpointPort());
        }
        Optional<String> loadBalancerHost = kubectl(config, "service load balancer hostname",
                "get", "service", config.serviceName(), "-o", "jsonpath={.status.loadBalancer.ingress[0].hostname}");
        if (loadBalancerHost.isPresent()) {
            return new ResolvedMinecraftEndpoint(loadBalancerHost.orElseThrow(), config.endpointPort());
        }
        Optional<String> nodePort = kubectl(config, "service nodePort",
                "get", "service", config.serviceName(), "-o", "jsonpath={.spec.ports[0].nodePort}");
        if (nodePort.isPresent()) {
            return new ResolvedMinecraftEndpoint(config.nodeHost(), Integer.parseInt(nodePort.orElseThrow()));
        }
        throw new IOException("Velocity L4 Service " + config.namespace() + "/" + config.serviceName()
                + " did not expose a load-balancer address or nodePort");
    }

    private static Optional<String> kubectl(VerificationConfig config, String label, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        config.kubeContext().ifPresent(context -> {
            command.add("--context");
            command.add(context);
        });
        command.add("-n");
        command.add(config.namespace());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean finished;
        try {
            finished = process.waitFor(config.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving " + label + " with kubectl", exception);
        }
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out resolving " + label + " with `" + String.join(" ", command) + "`");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Failed to resolve " + label + " with `" + String.join(" ", command)
                    + "`: " + output);
        }
        if (output.isBlank() || "<none>".equals(output)) {
            return Optional.empty();
        }
        return Optional.of(output);
    }

    private static int parseNonNegativeInteger(String value, String label) throws IOException {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IOException(label + " must be non-negative, got " + value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException(label + " must be an integer, got " + value, exception);
        }
    }

    record VerificationConfig(
            Optional<String> endpointHost,
            int endpointPort,
            String namespace,
            String serviceName,
            Optional<String> kubeContext,
            String nodeHost,
            String agonesFleetName,
            boolean verifyAgonesFleetState,
            int expectedAgonesAllocatedReplicas,
            int protocolVersion,
            String loginUsername,
            String secondLoginUsername,
            String expectedSpawnBlock,
            String expectedSpawnWorld,
            int expectedBedrockBlockX,
            int expectedBedrockBlockY,
            int expectedBedrockBlockZ,
            double expectedPlayerX,
            double expectedPlayerY,
            double expectedPlayerZ,
            double expectedPlayerYaw,
            double expectedPlayerPitch,
            String expectedDisplayName,
            String expectedRankLabel,
            String expectedDecoratedChatContains,
            String expectedSecondDisplayName,
            String expectedSecondRankLabel,
            String expectedSecondDecoratedChatContains,
            boolean verifyScaleOut,
            String scaleOutTriggerLoginUsername,
            Optional<String> scaleOutTriggerDeniedReasonContains,
            String scaleOutLoginUsername,
            String expectedScaleOutDisplayName,
            String expectedScaleOutRankLabel,
            String expectedScaleOutDecoratedChatContains,
            Duration scaleOutTimeout,
            Optional<String> deniedLoginUsername,
            Optional<String> deniedLoginReasonContains,
            Duration timeout) {
        private static final int DEFAULT_MINECRAFT_PORT = 25_565;
        private static final int DEFAULT_PROTOCOL_VERSION = 0;
        private static final String DEFAULT_LOGIN_USERNAME = "FulcrumBotOne";
        private static final String DEFAULT_SECOND_LOGIN_USERNAME = "FulcrumBotTwo";
        private static final String DEFAULT_AGONES_FLEET_NAME = "fulcrum-lobby-paper";
        private static final int DEFAULT_EXPECTED_AGONES_ALLOCATED_REPLICAS = 1;
        private static final String DEFAULT_EXPECTED_SPAWN_WORLD = "world";
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_X = 0;
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_Y = 64;
        private static final int DEFAULT_EXPECTED_BEDROCK_BLOCK_Z = 0;
        private static final double DEFAULT_EXPECTED_PLAYER_X = 0.5D;
        private static final double DEFAULT_EXPECTED_PLAYER_Y = 65.0D;
        private static final double DEFAULT_EXPECTED_PLAYER_Z = 0.5D;
        private static final double DEFAULT_EXPECTED_PLAYER_YAW = 0.0D;
        private static final double DEFAULT_EXPECTED_PLAYER_PITCH = 0.0D;
        private static final String DEFAULT_EXPECTED_DISPLAY_NAME = "Fulcrum Bot One";
        private static final String DEFAULT_EXPECTED_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot One: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;
        private static final String DEFAULT_EXPECTED_SECOND_DISPLAY_NAME = "Fulcrum Bot Two";
        private static final String DEFAULT_EXPECTED_SECOND_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_SECOND_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot Two: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;
        private static final String DEFAULT_SCALE_OUT_TRIGGER_LOGIN_USERNAME = "FulcrumBotThree";
        private static final String DEFAULT_SCALE_OUT_LOGIN_USERNAME = "FulcrumBotFour";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_DISPLAY_NAME = "Fulcrum Bot Four";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_RANK_LABEL = "Admin";
        private static final String DEFAULT_EXPECTED_SCALE_OUT_DECORATED_CHAT_CONTAINS =
                "[Admin] Fulcrum Bot Four: " + PaperLobbyProofMessage.PROOF_CHAT_MESSAGE;

        VerificationConfig {
            endpointHost = Objects.requireNonNull(endpointHost, "endpointHost")
                    .filter(value -> !value.isBlank());
            namespace = requireNonBlank(namespace, "namespace");
            serviceName = requireNonBlank(serviceName, "serviceName");
            kubeContext = Objects.requireNonNull(kubeContext, "kubeContext")
                    .filter(value -> !value.isBlank());
            nodeHost = requireNonBlank(nodeHost, "nodeHost");
            agonesFleetName = requireNonBlank(agonesFleetName, "agonesFleetName");
            loginUsername = requireNonBlank(loginUsername, "loginUsername");
            secondLoginUsername = requireNonBlank(secondLoginUsername, "secondLoginUsername");
            expectedSpawnBlock = requireNonBlank(expectedSpawnBlock, "expectedSpawnBlock");
            expectedSpawnWorld = requireNonBlank(expectedSpawnWorld, "expectedSpawnWorld");
            expectedDisplayName = requireNonBlank(expectedDisplayName, "expectedDisplayName");
            expectedRankLabel = requireNonBlank(expectedRankLabel, "expectedRankLabel");
            expectedDecoratedChatContains = requireNonBlank(
                    expectedDecoratedChatContains,
                    "expectedDecoratedChatContains");
            expectedSecondDisplayName = requireNonBlank(expectedSecondDisplayName, "expectedSecondDisplayName");
            expectedSecondRankLabel = requireNonBlank(expectedSecondRankLabel, "expectedSecondRankLabel");
            expectedSecondDecoratedChatContains = requireNonBlank(
                    expectedSecondDecoratedChatContains,
                    "expectedSecondDecoratedChatContains");
            scaleOutTriggerLoginUsername = requireNonBlank(
                    scaleOutTriggerLoginUsername,
                    "scaleOutTriggerLoginUsername");
            scaleOutTriggerDeniedReasonContains = Objects.requireNonNull(
                    scaleOutTriggerDeniedReasonContains,
                    "scaleOutTriggerDeniedReasonContains")
                    .filter(value -> !value.isBlank());
            scaleOutLoginUsername = requireNonBlank(scaleOutLoginUsername, "scaleOutLoginUsername");
            expectedScaleOutDisplayName = requireNonBlank(
                    expectedScaleOutDisplayName,
                    "expectedScaleOutDisplayName");
            expectedScaleOutRankLabel = requireNonBlank(expectedScaleOutRankLabel, "expectedScaleOutRankLabel");
            expectedScaleOutDecoratedChatContains = requireNonBlank(
                    expectedScaleOutDecoratedChatContains,
                    "expectedScaleOutDecoratedChatContains");
            scaleOutTimeout = Objects.requireNonNull(scaleOutTimeout, "scaleOutTimeout");
            deniedLoginUsername = Objects.requireNonNull(deniedLoginUsername, "deniedLoginUsername")
                    .filter(value -> !value.isBlank());
            deniedLoginReasonContains = Objects.requireNonNull(
                    deniedLoginReasonContains,
                    "deniedLoginReasonContains")
                    .filter(value -> !value.isBlank());
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (endpointPort < 1 || endpointPort > 65_535) {
                throw new IllegalArgumentException("endpointPort out of range: " + endpointPort);
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (scaleOutTimeout.isNegative() || scaleOutTimeout.isZero()) {
                throw new IllegalArgumentException("scaleOutTimeout must be positive");
            }
            if (expectedAgonesAllocatedReplicas < 0) {
                throw new IllegalArgumentException("expectedAgonesAllocatedReplicas must be non-negative");
            }
            requireFinite(expectedPlayerX, "expectedPlayerX");
            requireFinite(expectedPlayerY, "expectedPlayerY");
            requireFinite(expectedPlayerZ, "expectedPlayerZ");
            requireFinite(expectedPlayerYaw, "expectedPlayerYaw");
            requireFinite(expectedPlayerPitch, "expectedPlayerPitch");
        }

        static VerificationConfig parse(String[] args) {
            Optional<String> endpointHost = Optional.empty();
            int endpointPort = DEFAULT_MINECRAFT_PORT;
            String namespace = "fulcrum-lobby";
            String serviceName = "fulcrum-velocity-l4";
            Optional<String> kubeContext = Optional.empty();
            String nodeHost = "127.0.0.1";
            int protocolVersion = DEFAULT_PROTOCOL_VERSION;
            String loginUsername = DEFAULT_LOGIN_USERNAME;
            String secondLoginUsername = DEFAULT_SECOND_LOGIN_USERNAME;
            String agonesFleetName = DEFAULT_AGONES_FLEET_NAME;
            Optional<Boolean> verifyAgonesFleetState = Optional.empty();
            int expectedAgonesAllocatedReplicas = DEFAULT_EXPECTED_AGONES_ALLOCATED_REPLICAS;
            String expectedSpawnBlock = PaperLobbyProofMessage.SPAWN_BLOCK;
            String expectedSpawnWorld = DEFAULT_EXPECTED_SPAWN_WORLD;
            int expectedBedrockBlockX = DEFAULT_EXPECTED_BEDROCK_BLOCK_X;
            int expectedBedrockBlockY = DEFAULT_EXPECTED_BEDROCK_BLOCK_Y;
            int expectedBedrockBlockZ = DEFAULT_EXPECTED_BEDROCK_BLOCK_Z;
            double expectedPlayerX = DEFAULT_EXPECTED_PLAYER_X;
            double expectedPlayerY = DEFAULT_EXPECTED_PLAYER_Y;
            double expectedPlayerZ = DEFAULT_EXPECTED_PLAYER_Z;
            double expectedPlayerYaw = DEFAULT_EXPECTED_PLAYER_YAW;
            double expectedPlayerPitch = DEFAULT_EXPECTED_PLAYER_PITCH;
            String expectedDisplayName = DEFAULT_EXPECTED_DISPLAY_NAME;
            String expectedRankLabel = DEFAULT_EXPECTED_RANK_LABEL;
            String expectedDecoratedChatContains = DEFAULT_EXPECTED_DECORATED_CHAT_CONTAINS;
            String expectedSecondDisplayName = DEFAULT_EXPECTED_SECOND_DISPLAY_NAME;
            String expectedSecondRankLabel = DEFAULT_EXPECTED_SECOND_RANK_LABEL;
            String expectedSecondDecoratedChatContains = DEFAULT_EXPECTED_SECOND_DECORATED_CHAT_CONTAINS;
            boolean verifyScaleOut = false;
            String scaleOutTriggerLoginUsername = DEFAULT_SCALE_OUT_TRIGGER_LOGIN_USERNAME;
            Optional<String> scaleOutTriggerDeniedReasonContains =
                    Optional.of(VelocityLoginRoutingEvaluator.NO_LOBBY_ROUTE_REASON);
            String scaleOutLoginUsername = DEFAULT_SCALE_OUT_LOGIN_USERNAME;
            String expectedScaleOutDisplayName = DEFAULT_EXPECTED_SCALE_OUT_DISPLAY_NAME;
            String expectedScaleOutRankLabel = DEFAULT_EXPECTED_SCALE_OUT_RANK_LABEL;
            String expectedScaleOutDecoratedChatContains = DEFAULT_EXPECTED_SCALE_OUT_DECORATED_CHAT_CONTAINS;
            Duration scaleOutTimeout = Duration.ofSeconds(60);
            Optional<String> deniedLoginUsername = Optional.empty();
            Optional<String> deniedLoginReasonContains = Optional.empty();
            Duration timeout = Duration.ofSeconds(10);

            for (String arg : args) {
                if (arg.startsWith("--endpoint-host=")) {
                    endpointHost = Optional.of(value(arg));
                } else if (arg.startsWith("--endpoint-port=")) {
                    endpointPort = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--namespace=")) {
                    namespace = value(arg);
                } else if (arg.startsWith("--service=")) {
                    serviceName = value(arg);
                } else if (arg.startsWith("--kube-context=")) {
                    kubeContext = Optional.of(value(arg));
                } else if (arg.startsWith("--node-host=")) {
                    nodeHost = value(arg);
                } else if (arg.startsWith("--protocol-version=")) {
                    protocolVersion = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--login-username=")) {
                    loginUsername = value(arg);
                } else if (arg.startsWith("--second-login-username=")) {
                    secondLoginUsername = value(arg);
                } else if (arg.startsWith("--agones-fleet-name=")) {
                    agonesFleetName = value(arg);
                } else if (arg.startsWith("--verify-agones-fleet-state=")) {
                    verifyAgonesFleetState = Optional.of(parseBoolean(value(arg), "verifyAgonesFleetState"));
                } else if (arg.startsWith("--expected-agones-allocated-replicas=")) {
                    expectedAgonesAllocatedReplicas = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-spawn-block=")) {
                    expectedSpawnBlock = value(arg);
                } else if (arg.startsWith("--expected-lobby-spawn-world=")) {
                    expectedSpawnWorld = value(arg);
                } else if (arg.startsWith("--expected-lobby-bedrock-block-x=")) {
                    expectedBedrockBlockX = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-bedrock-block-y=")) {
                    expectedBedrockBlockY = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-bedrock-block-z=")) {
                    expectedBedrockBlockZ = Integer.parseInt(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-x=")) {
                    expectedPlayerX = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-y=")) {
                    expectedPlayerY = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-z=")) {
                    expectedPlayerZ = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-yaw=")) {
                    expectedPlayerYaw = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-player-pitch=")) {
                    expectedPlayerPitch = Double.parseDouble(value(arg));
                } else if (arg.startsWith("--expected-lobby-display-name=")) {
                    expectedDisplayName = value(arg);
                } else if (arg.startsWith("--expected-lobby-rank-label=")) {
                    expectedRankLabel = value(arg);
                } else if (arg.startsWith("--expected-lobby-decorated-chat-contains=")) {
                    expectedDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-display-name=")) {
                    expectedSecondDisplayName = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-rank-label=")) {
                    expectedSecondRankLabel = value(arg);
                } else if (arg.startsWith("--expected-second-lobby-decorated-chat-contains=")) {
                    expectedSecondDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--verify-scale-out=")) {
                    verifyScaleOut = parseBoolean(value(arg), "verifyScaleOut");
                } else if (arg.startsWith("--scale-out-trigger-login-username=")) {
                    scaleOutTriggerLoginUsername = value(arg);
                } else if (arg.startsWith("--scale-out-trigger-denied-reason-contains=")) {
                    scaleOutTriggerDeniedReasonContains = Optional.of(value(arg));
                } else if (arg.startsWith("--scale-out-login-username=")) {
                    scaleOutLoginUsername = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-display-name=")) {
                    expectedScaleOutDisplayName = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-rank-label=")) {
                    expectedScaleOutRankLabel = value(arg);
                } else if (arg.startsWith("--expected-scale-out-lobby-decorated-chat-contains=")) {
                    expectedScaleOutDecoratedChatContains = value(arg);
                } else if (arg.startsWith("--scale-out-timeout=")) {
                    scaleOutTimeout = Duration.parse(value(arg));
                } else if (arg.startsWith("--denied-login-username=")) {
                    deniedLoginUsername = Optional.of(value(arg));
                } else if (arg.startsWith("--denied-login-reason-contains=")) {
                    deniedLoginReasonContains = Optional.of(value(arg));
                } else if (arg.startsWith("--timeout=")) {
                    timeout = Duration.parse(value(arg));
                } else {
                    throw new IllegalArgumentException("Unsupported lobby cluster E2E verifier argument: " + arg);
                }
            }
            return new VerificationConfig(
                    endpointHost,
                    endpointPort,
                    namespace,
                    serviceName,
                    kubeContext,
                    nodeHost,
                    agonesFleetName,
                    verifyAgonesFleetState.orElse(endpointHost.isEmpty()),
                    expectedAgonesAllocatedReplicas,
                    protocolVersion,
                    loginUsername,
                    secondLoginUsername,
                    expectedSpawnBlock,
                    expectedSpawnWorld,
                    expectedBedrockBlockX,
                    expectedBedrockBlockY,
                    expectedBedrockBlockZ,
                    expectedPlayerX,
                    expectedPlayerY,
                    expectedPlayerZ,
                    expectedPlayerYaw,
                    expectedPlayerPitch,
                    expectedDisplayName,
                    expectedRankLabel,
                    expectedDecoratedChatContains,
                    expectedSecondDisplayName,
                    expectedSecondRankLabel,
                    expectedSecondDecoratedChatContains,
                    verifyScaleOut,
                    scaleOutTriggerLoginUsername,
                    scaleOutTriggerDeniedReasonContains,
                    scaleOutLoginUsername,
                    expectedScaleOutDisplayName,
                    expectedScaleOutRankLabel,
                    expectedScaleOutDecoratedChatContains,
                    scaleOutTimeout,
                    deniedLoginUsername,
                    deniedLoginReasonContains,
                    timeout);
        }

        private static String value(String arg) {
            return arg.substring(arg.indexOf('=') + 1);
        }

        private static boolean parseBoolean(String value, String name) {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false, got " + value);
        }
    }

    record ResolvedMinecraftEndpoint(String host, int port) {
        ResolvedMinecraftEndpoint {
            host = requireNonBlank(host, "host");
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }
    }

    private record ScaleOutProof(
            LoginAttemptResult triggerDeniedLogin,
            PaperLobbyProofMessage acceptedLoginProof) {
        private ScaleOutProof {
            triggerDeniedLogin = Objects.requireNonNull(triggerDeniedLogin, "triggerDeniedLogin");
            acceptedLoginProof = Objects.requireNonNull(acceptedLoginProof, "acceptedLoginProof");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
