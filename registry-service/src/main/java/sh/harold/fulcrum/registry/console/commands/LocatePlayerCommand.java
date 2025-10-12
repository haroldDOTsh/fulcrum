package sh.harold.fulcrum.registry.console.commands;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateRequest;
import sh.harold.fulcrum.api.messagebus.messages.PlayerLocateResponse;
import sh.harold.fulcrum.registry.console.CommandHandler;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Console command that locates a player across all proxies.
 */
public class LocatePlayerCommand implements CommandHandler {
    private static final long TIMEOUT_MS = 3000L;

    private final MessageBus messageBus;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LocatePlayerCommand(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: " + getUsage());
            return false;
        }

        String target = args[1];
        UUID targetId = parseUuid(target);
        String targetName = targetId == null ? target : null;

        PlayerLocateRequest request = new PlayerLocateRequest();
        UUID requestId = UUID.randomUUID();
        request.setRequestId(requestId);
        if (targetId != null) {
            request.setPlayerId(targetId);
        }
        if (targetName != null) {
            request.setPlayerName(targetName);
        }

        try {
            request.validate();
        } catch (IllegalStateException exception) {
            System.out.println("Invalid locate request: " + exception.getMessage());
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PlayerLocateResponse> responseRef = new AtomicReference<>();

        MessageHandler handler = envelope -> {
            PlayerLocateResponse response = convert(envelope, PlayerLocateResponse.class);
            if (response == null || !requestId.equals(response.getRequestId())) {
                return;
            }
            responseRef.compareAndSet(null, response);
            latch.countDown();
        };

        messageBus.subscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);
        try {
            messageBus.broadcast(ChannelConstants.REGISTRY_PLAYER_LOCATE_REQUEST, request);
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                System.out.println("Player " + (targetName != null ? targetName : targetId) + " not found within timeout.");
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.out.println("Locate command interrupted.");
            return false;
        } finally {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_LOCATE_RESPONSE, handler);
        }

        PlayerLocateResponse response = responseRef.get();
        if (response == null || !response.isFound()) {
            System.out.println("Player " + (targetName != null ? targetName : targetId) + " is not currently online.");
            return false;
        }

        String resolvedName = response.getPlayerName() != null ? response.getPlayerName()
                : (response.getPlayerId() != null ? response.getPlayerId().toString() : "unknown");
        String serverId = response.getServerId() != null ? response.getServerId() : "unknown";
        String slotSuffix = response.getSlotSuffix();
        String family = response.getFamilyId() != null && !response.getFamilyId().isBlank()
                ? response.getFamilyId()
                : "unknown";

        String serverDisplay = slotSuffix != null && !slotSuffix.isBlank() ? serverId + slotSuffix : serverId;
        System.out.println("Player " + resolvedName + " spotted on " + serverDisplay + " (" + family + ")");
        return true;
    }

    @Override
    public String getName() {
        return "locateplayer";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"whereis"};
    }

    @Override
    public String getDescription() {
        return "Locate a player across all proxies.";
    }

    @Override
    public String getUsage() {
        return "locateplayer <player>";
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private <T> T convert(MessageEnvelope envelope, Class<T> type) {
        if (envelope == null || envelope.getPayload() == null) {
            return null;
        }
        Object payload = envelope.getPayload();
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }
}
