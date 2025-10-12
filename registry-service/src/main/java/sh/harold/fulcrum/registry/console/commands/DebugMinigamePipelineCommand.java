package sh.harold.fulcrum.registry.console.commands;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateResponse;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;

public class DebugMinigamePipelineCommand implements CommandHandler {
    private static final String FAMILY_ID = "debug_pipeline";
    private static final long LOCATE_TIMEOUT_MS = 2000L;

    private final MessageBus messageBus;
    private final ProxyRegistry proxyRegistry;
    private final ObjectMapper objectMapper;

    public DebugMinigamePipelineCommand(MessageBus messageBus, ProxyRegistry proxyRegistry) {
        this.messageBus = messageBus;
        this.proxyRegistry = proxyRegistry;
        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: debugminigamepipeline <playerName> [uuid=<uuid>] [proxy=<proxyId>] [reason=<reason>] [map=<mapId>] [key=value ...]");
            return false;
        }

        String playerName = args[1];
        ParsedArguments parsed = parseArguments(args);
        if (parsed.invalid) {
            return false;
        }

        UUID suppliedId = null;
        if (parsed.uuid != null) {
            try {
                suppliedId = UUID.fromString(parsed.uuid);
            } catch (IllegalArgumentException exception) {
                System.out.println("Invalid UUID format: " + parsed.uuid);
                return false;
            }
        }

        LocateResult target = resolveTarget(playerName, suppliedId, parsed.proxyId);
        if (target == null) {
            return false;
        }

        UUID playerId = target.playerId() != null ? target.playerId() :
            (suppliedId != null ? suppliedId : offlineUuid(playerName));
        String effectiveName = target.playerName() != null ? target.playerName() : playerName;
        String proxyId = target.proxyId();

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("player", effectiveName);
        metadata.put("source", "debugminigamepipeline-command");
        metadata.put("initiator", "registry-console");
        metadata.put("reason", parsed.reason);
        metadata.put("mapId", parsed.mapId);
        parsed.extraMetadata.forEach(metadata::putIfAbsent);

        PlayerSlotRequest request = new PlayerSlotRequest();
        request.setPlayerId(playerId);
        request.setPlayerName(effectiveName);
        request.setProxyId(proxyId);
        request.setFamilyId(FAMILY_ID);
        request.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_REQUEST, request);

        System.out.println("Enqueued player slot request for " + effectiveName
            + " (uuid=" + request.getPlayerId() + ", proxy=" + proxyId
            + ", map=" + parsed.mapId + ", requestId=" + request.getRequestId() + ")");
        System.out.println("Await backend logs for provisioning confirmation and routing outcome.");
        return true;
    }

    @Override
    public String getName() {
        return "debugminigamepipeline";
    }

    @Override
    public String[] getAliases() {
        return new String[] {"dminipipeline", "dmpipeline"};
    }

    @Override
    public String getDescription() {
        return "Provision and validate the debug minigame pipeline flow for the specified player";
    }

    @Override
    public String getUsage() {
        return "debugminigamepipeline <playerName> [uuid=<uuid>] [proxy=<proxyId>] [reason=<reason>] [map=<mapId>] [key=value ...]";
    }

    private ParsedArguments parseArguments(String[] args) {
        ParsedArguments parsed = new ParsedArguments();

        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                System.out.println("Invalid argument format: " + token + ". Expected key=value.");
                parsed.invalid = true;
                return parsed;
            }
            String key = token.substring(0, eq).trim().toLowerCase();
            String value = token.substring(eq + 1).trim();
            switch (key) {
                case "uuid" -> parsed.uuid = value;
                case "proxy", "proxyid" -> parsed.proxyId = value;
                case "reason" -> parsed.reason = value;
                case "map", "mapid" -> parsed.mapId = value;
                default -> parsed.extraMetadata.put(key, value);
            }
        }
        return parsed;
    }

    private LocateResult resolveTarget(String playerName, UUID suppliedId, String requestedProxyId) {
        if (requestedProxyId != null && !requestedProxyId.isBlank()) {
            if (proxyRegistry.getProxy(requestedProxyId) == null) {
                System.out.println("Proxy '" + requestedProxyId + "' is not registered.");
                return null;
            }
            return new LocateResult(requestedProxyId, suppliedId, playerName, null, null, null);
        }

        LocateResult located = locateProxyForPlayer(suppliedId, playerName);
        if (located != null && located.proxyId() != null) {
            String serverDisplay = located.slotSuffix() != null && !located.slotSuffix().isBlank()
                ? located.serverId() + located.slotSuffix()
                : located.serverId() != null ? located.serverId() : "unknown";
            String familyDisplay = located.familyId() != null && !located.familyId().isBlank()
                ? located.familyId()
                : "unknown";
            System.out.println("Located player on proxy " + located.proxyId()
                + " => " + serverDisplay + " (" + familyDisplay + ")");
            return located;
        }

        Collection<RegisteredProxyData> proxies = proxyRegistry.getAllProxies();
        if (proxies.size() == 1) {
            String fallback = proxies.iterator().next().getProxyIdString();
            System.out.println("Player not located; defaulting to proxy " + fallback + " (single proxy environment).");
            return new LocateResult(fallback, suppliedId, playerName, null, null, null);
        }

        System.out.println("Player " + playerName + " is not currently online on any known proxy.");
        return null;
    }

    private LocateResult locateProxyForPlayer(UUID playerId, String playerName) {
        UUID requestId = UUID.randomUUID();
        PlayerLocateRequest request = new PlayerLocateRequest();
        request.setRequestId(requestId);
        request.setPlayerId(playerId);
        request.setPlayerName(playerName);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<LocateResult> resultRef = new AtomicReference<>();

        MessageHandler handler = envelope -> {
            try {
                PlayerLocateResponse response = convert(envelope.getPayload(), PlayerLocateResponse.class);
                if (response == null || !requestId.equals(response.getRequestId()) || !response.isFound()) {
                    return;
                }
                LocateResult located = new LocateResult(
                    response.getProxyId(),
                    response.getPlayerId() != null ? response.getPlayerId() : playerId,
                    response.getPlayerName() != null ? response.getPlayerName() : playerName,
                    response.getServerId(),
                    response.getSlotSuffix(),
                    response.getFamilyId());
                if (resultRef.compareAndSet(null, located)) {
                    latch.countDown();
                }
            } catch (Exception ignored) {
            }
        };

        messageBus.subscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);
        try {
            messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_LOCATE_REQUEST, request);
            if (!latch.await(LOCATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.out.println("Timed out waiting for locate response for " + playerName + ".");
                return null;
            }
            return resultRef.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("Locate request interrupted.");
            return null;
        } finally {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);
        }
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }

    private UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private static final class ParsedArguments {
        String uuid;
        String proxyId;
        String reason = "manual-request";
        String mapId = "test";
        final Map<String, String> extraMetadata = new LinkedHashMap<>();
        boolean invalid;
    }

    private record LocateResult(String proxyId,
                                UUID playerId,
                                String playerName,
                                String serverId,
                                String slotSuffix,
                                String familyId) {}
}
