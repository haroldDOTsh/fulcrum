package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.ResolvedManifestId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.api.kernel.SlotId;
import sh.harold.fulcrum.api.kernel.SubjectId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PaperLobbyProofMessage(
        InstanceId instanceId,
        SessionId sessionId,
        RouteId routeId,
        SlotId slotId,
        ResolvedManifestId resolvedManifestId,
        String traceId,
        SubjectId subjectId,
        String spawnWorld,
        int bedrockBlockX,
        int bedrockBlockY,
        int bedrockBlockZ,
        double playerX,
        double playerY,
        double playerZ,
        float playerYaw,
        float playerPitch,
        String displayName,
        Optional<String> rankLabel,
        String decoratedChat) {
    public static final String CHANNEL = "fulcrum:lobby_probe";
    public static final String MARKER = "FULCRUM_LOBBY_PROOF";
    public static final String SPAWN_BLOCK = "bedrock";
    public static final String PROOF_CHAT_MESSAGE = "fulcrum-proof-chat";

    public PaperLobbyProofMessage {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        routeId = Objects.requireNonNull(routeId, "routeId");
        slotId = Objects.requireNonNull(slotId, "slotId");
        resolvedManifestId = Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        traceId = PaperArtifactNames.requireNonBlank(traceId, "traceId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        spawnWorld = PaperArtifactNames.requireNonBlank(spawnWorld, "spawnWorld");
        requireFinite(playerX, "playerX");
        requireFinite(playerY, "playerY");
        requireFinite(playerZ, "playerZ");
        requireFinite(playerYaw, "playerYaw");
        requireFinite(playerPitch, "playerPitch");
        displayName = PaperArtifactNames.requireNonBlank(displayName, "displayName");
        rankLabel = rankLabel == null ? Optional.empty() : rankLabel
                .map(value -> PaperArtifactNames.requireNonBlank(value, "rankLabel"));
        decoratedChat = PaperArtifactNames.requireNonBlank(decoratedChat, "decoratedChat");
    }

    static PaperLobbyProofMessage from(
            InstanceId instanceId,
            SessionId sessionId,
            RouteId routeId,
            SlotId slotId,
            ResolvedManifestId resolvedManifestId,
            String traceId,
            PaperSpawnPoint spawnPoint,
            double playerX,
            double playerY,
            double playerZ,
            float playerYaw,
            float playerPitch,
            PaperSubjectCapabilityView subjectView,
            PaperChatDecorationResponse chatDecoration) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(resolvedManifestId, "resolvedManifestId");
        PaperArtifactNames.requireNonBlank(traceId, "traceId");
        Objects.requireNonNull(spawnPoint, "spawnPoint");
        Objects.requireNonNull(subjectView, "subjectView");
        Objects.requireNonNull(chatDecoration, "chatDecoration");
        return new PaperLobbyProofMessage(
                instanceId,
                sessionId,
                routeId,
                slotId,
                resolvedManifestId,
                traceId,
                subjectView.subjectId(),
                spawnPoint.worldName(),
                spawnPoint.bedrockBlockX(),
                spawnPoint.bedrockBlockY(),
                spawnPoint.bedrockBlockZ(),
                playerX,
                playerY,
                playerZ,
                playerYaw,
                playerPitch,
                subjectView.displayName(),
                subjectView.rankLabel(),
                chatDecoration.decoratedMessage());
    }

    public byte[] encode() {
        return asText().getBytes(StandardCharsets.UTF_8);
    }

    public String asText() {
        return String.join("\n",
                MARKER,
                "instanceId=" + encodeValue(instanceId.value()),
                "sessionId=" + encodeValue(sessionId.value()),
                "routeId=" + encodeValue(routeId.value()),
                "slotId=" + encodeValue(slotId.value()),
                "resolvedManifestId=" + encodeValue(resolvedManifestId.value()),
                "traceId=" + encodeValue(traceId),
                "subjectId=" + subjectId.value(),
                "spawnWorld=" + encodeValue(spawnWorld),
                "spawnBlock=" + SPAWN_BLOCK,
                "bedrockBlockX=" + bedrockBlockX,
                "bedrockBlockY=" + bedrockBlockY,
                "bedrockBlockZ=" + bedrockBlockZ,
                "playerX=" + playerX,
                "playerY=" + playerY,
                "playerZ=" + playerZ,
                "playerYaw=" + playerYaw,
                "playerPitch=" + playerPitch,
                "displayName=" + encodeValue(displayName),
                "rankLabel=" + rankLabel.map(PaperLobbyProofMessage::encodeValue).orElse(""),
                "decoratedChat=" + encodeValue(decoratedChat));
    }

    public static PaperLobbyProofMessage parse(String payload) {
        String checked = PaperArtifactNames.requireNonBlank(payload, "payload");
        int markerIndex = checked.indexOf(MARKER);
        if (markerIndex < 0) {
            throw new IllegalArgumentException("Lobby proof payload did not contain marker " + MARKER);
        }
        String proof = checked.substring(markerIndex);
        String[] lines = proof.split("\\R");
        if (lines.length == 0 || !MARKER.equals(lines[0])) {
            throw new IllegalArgumentException("Lobby proof payload did not start with " + MARKER);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int separator = lines[i].indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed lobby proof line: " + lines[i]);
            }
            fields.put(lines[i].substring(0, separator), lines[i].substring(separator + 1));
        }
        if (!SPAWN_BLOCK.equals(required(fields, "spawnBlock"))) {
            throw new IllegalArgumentException("Unsupported lobby proof spawnBlock " + fields.get("spawnBlock"));
        }
        return new PaperLobbyProofMessage(
                new InstanceId(decodeValue(required(fields, "instanceId"))),
                new SessionId(decodeValue(required(fields, "sessionId"))),
                new RouteId(decodeValue(required(fields, "routeId"))),
                new SlotId(decodeValue(required(fields, "slotId"))),
                new ResolvedManifestId(decodeValue(required(fields, "resolvedManifestId"))),
                decodeValue(required(fields, "traceId")),
                new SubjectId(UUID.fromString(required(fields, "subjectId"))),
                decodeValue(required(fields, "spawnWorld")),
                Integer.parseInt(required(fields, "bedrockBlockX")),
                Integer.parseInt(required(fields, "bedrockBlockY")),
                Integer.parseInt(required(fields, "bedrockBlockZ")),
                Double.parseDouble(required(fields, "playerX")),
                Double.parseDouble(required(fields, "playerY")),
                Double.parseDouble(required(fields, "playerZ")),
                Float.parseFloat(required(fields, "playerYaw")),
                Float.parseFloat(required(fields, "playerPitch")),
                decodeValue(required(fields, "displayName")),
                Optional.ofNullable(fields.get("rankLabel"))
                        .filter(value -> !value.isBlank())
                        .map(PaperLobbyProofMessage::decodeValue),
                decodeValue(required(fields, "decoratedChat")));
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Lobby proof payload missing " + key);
        }
        return value;
    }

    private static String encodeValue(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(PaperArtifactNames.requireNonBlank(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(Objects.requireNonNull(label, "label") + " must be finite");
        }
    }
}
