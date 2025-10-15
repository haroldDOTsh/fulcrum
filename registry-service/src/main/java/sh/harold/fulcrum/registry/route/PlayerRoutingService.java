package sh.harold.fulcrum.registry.route;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;
import sh.harold.fulcrum.registry.slot.SlotProvisionService.ProvisionResult;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinates player matchmaking between proxies and backend slots.
 */
public class PlayerRoutingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerRoutingService.class);

    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_QUEUE_WAIT = Duration.ofSeconds(45);
    private static final int MAX_ROUTE_RETRIES = 3;

    private static final Set<String> RETRYABLE_FAILURES = Set.of(
            "backend-not-found",
            "backend-offline",
            "connection-failed",
            "slot-not-ready",
            "route-transient"
    );

    private final MessageBus messageBus;
    private final SlotProvisionService slotProvisionService;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentMap<String, Deque<PlayerRequestContext>> pendingQueues = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, InFlightRoute> inFlightRoutes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> pendingOccupancy = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> provisioningFamilies = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CompletableFuture<PlayerReservationResponse>> pendingReservations = new ConcurrentHashMap<>();

    private final MessageHandler playerRequestHandler = this::handlePlayerRequest;
    private final MessageHandler slotStatusHandler = this::handleSlotStatus;
    private final MessageHandler routeAckHandler = this::handleRouteAck;
    private final MessageHandler reservationResponseHandler = this::handleReservationResponse;

    public PlayerRoutingService(MessageBus messageBus,
                                SlotProvisionService slotProvisionService,
                                ServerRegistry serverRegistry,
                                ProxyRegistry proxyRegistry) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.slotProvisionService = Objects.requireNonNull(slotProvisionService, "slotProvisionService");
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry");
        this.proxyRegistry = Objects.requireNonNull(proxyRegistry, "proxyRegistry");
        this.objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "PlayerRoutingService");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void initialize() {
        messageBus.subscribe(ChannelConstants.REGISTRY_PLAYER_REQUEST, playerRequestHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_SLOT_STATUS, slotStatusHandler);
        messageBus.subscribe(ChannelConstants.PLAYER_ROUTE_ACK, routeAckHandler);
        messageBus.subscribe(ChannelConstants.PLAYER_RESERVATION_RESPONSE, reservationResponseHandler);

        seedAvailableSlots();
        LOGGER.info("PlayerRoutingService subscribed to matchmaking channels");
    }

    public void shutdown() {
        scheduler.shutdownNow();
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_REQUEST, playerRequestHandler);
        messageBus.unsubscribe(ChannelConstants.REGISTRY_SLOT_STATUS, slotStatusHandler);
        messageBus.unsubscribe(ChannelConstants.PLAYER_ROUTE_ACK, routeAckHandler);
        messageBus.unsubscribe(ChannelConstants.PLAYER_RESERVATION_RESPONSE, reservationResponseHandler);
        LOGGER.info("PlayerRoutingService shut down");
    }

    private void seedAvailableSlots() {
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            for (LogicalSlotRecord slot : server.getSlots()) {
                if (SlotLifecycleStatus.AVAILABLE == slot.getStatus()) {
                    String familyId = slot.getMetadata().get("family");
                    if (familyId != null) {
                        dispatchQueuedPlayers(familyId, slot);
                    }
                }
            }
        }
    }

    private void handlePlayerRequest(MessageEnvelope envelope) {
        try {
            PlayerSlotRequest request = convert(envelope.getPayload(), PlayerSlotRequest.class);
            request.validate();

            RegisteredProxyData proxy = proxyRegistry.getProxy(request.getProxyId());
            if (proxy == null) {
                LOGGER.warn("Received player slot request for unknown proxy {}", request.getProxyId());
                sendDisconnectCommand(request, "unknown-proxy");
                return;
            }

            Optional<LogicalSlotRecord> available = findAvailableSlot(request.getFamilyId());
            if (available.isPresent()) {
                PlayerRequestContext context = new PlayerRequestContext(request);
                routePlayer(context, available.get());
                return;
            }

            PlayerRequestContext context = new PlayerRequestContext(request);
            enqueueContext(context);
            triggerProvisionIfNeeded(request.getFamilyId(), request.getMetadata());
        } catch (Exception exception) {
            LOGGER.error("Failed to handle PlayerSlotRequest", exception);
        }
    }

    private void handleSlotStatus(MessageEnvelope envelope) {
        try {
            SlotStatusUpdateMessage update = convert(envelope.getPayload(), SlotStatusUpdateMessage.class);
            LogicalSlotRecord slot = serverRegistry.updateSlot(update.getServerId(), update);
            if (slot == null) {
                return;
            }

            SlotLifecycleStatus status = slot.getStatus();
            String familyId = slot.getMetadata().get("family");
            if (status == SlotLifecycleStatus.AVAILABLE) {
                if (familyId == null) {
                    return;
                }
                dispatchQueuedPlayers(familyId, slot);
                return;
            }

            if (status == SlotLifecycleStatus.FAULTED
                    || status == SlotLifecycleStatus.PROVISIONING
                    || status == SlotLifecycleStatus.COOLDOWN) {
                handleSlotUnavailable(slot, "slot-unavailable");
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle slot status update in PlayerRoutingService", exception);
        }
    }

    private void handleRouteAck(MessageEnvelope envelope) {
        try {
            PlayerRouteAck ack = convert(envelope.getPayload(), PlayerRouteAck.class);
            ack.validate();

            InFlightRoute route = inFlightRoutes.remove(ack.getRequestId());
            if (route != null) {
                if (route.timeoutFuture != null) {
                    route.timeoutFuture.cancel(false);
                }
                adjustPendingOccupancy(route.slotId, -1);
            }

            if (ack.getStatus() == PlayerRouteAck.Status.SUCCESS) {
                LOGGER.debug("Player {} successfully routed to slot {}", ack.getPlayerId(), ack.getSlotId());
                return;
            }

            LOGGER.warn("Player routing failed for request {} (reason: {})", ack.getRequestId(), ack.getReason());

            if (route == null) {
                return;
            }

            PlayerRequestContext context = route.request;
            if (shouldRetry(ack.getReason())) {
                retryRequest(context, ack.getReason());
            } else {
                sendDisconnectCommand(context.request, ack.getReason() != null ? ack.getReason() : "route-failed");
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle PlayerRouteAck", exception);
        }
    }

    private CompletableFuture<PlayerReservationResponse> requestReservation(PlayerRequestContext context, LogicalSlotRecord slot) {
        UUID reservationId = UUID.randomUUID();
        PlayerReservationRequest reservationRequest = new PlayerReservationRequest();
        reservationRequest.setRequestId(reservationId);
        reservationRequest.setPlayerId(context.request.getPlayerId());
        reservationRequest.setPlayerName(context.request.getPlayerName());
        reservationRequest.setProxyId(context.request.getProxyId());
        reservationRequest.setServerId(slot.getServerId());
        reservationRequest.setSlotId(slot.getSlotId());
        reservationRequest.setMetadata(context.request.getMetadata());

        CompletableFuture<PlayerReservationResponse> future = new CompletableFuture<>();
        CompletableFuture<PlayerReservationResponse> existing = pendingReservations.putIfAbsent(reservationId, future);
        if (existing != null) {
            return existing;
        }

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CompletableFuture<PlayerReservationResponse> pending = pendingReservations.remove(reservationId);
            if (pending != null) {
                pending.completeExceptionally(new TimeoutException("reservation-timeout"));
            }
        }, RESERVATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        future.whenComplete((response, throwable) -> timeoutTask.cancel(false));

        try {
            messageBus.send(slot.getServerId(), ChannelConstants.PLAYER_RESERVATION_REQUEST, reservationRequest);
        } catch (Exception exception) {
            CompletableFuture<PlayerReservationResponse> pending = pendingReservations.remove(reservationId);
            if (pending != null) {
                pending.completeExceptionally(exception);
            } else {
                future.completeExceptionally(exception);
            }
        }

        return future;
    }

    private void handleReservationResponse(MessageEnvelope envelope) {
        try {
            PlayerReservationResponse response = convert(envelope.getPayload(), PlayerReservationResponse.class);
            response.validate();
            CompletableFuture<PlayerReservationResponse> future = pendingReservations.remove(response.getRequestId());
            if (future != null) {
                future.complete(response);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle reservation response", exception);
        }
    }

    private void enqueueContext(PlayerRequestContext context) {
        if (context == null) {
            return;
        }
        context.markEnqueued();
        pendingQueues.compute(context.request.getFamilyId(), (family, queue) -> {
            Deque<PlayerRequestContext> effective = queue != null ? queue : new ConcurrentLinkedDeque<>();
            effective.addLast(context);
            return effective;
        });
    }

    private void triggerProvisionIfNeeded(String familyId, Map<String, String> metadata) {
        if (familyId == null || familyId.isBlank()) {
            return;
        }

        provisioningFamilies.compute(familyId, (family, inFlight) -> {
            if (Boolean.TRUE.equals(inFlight)) {
                return Boolean.TRUE;
            }

            Optional<ProvisionResult> result = slotProvisionService.requestProvision(familyId, metadata);
            result.ifPresent(provision -> LOGGER.info(
                    "Triggered slot provision for family {} on server {}", familyId, provision.serverId()));
            return result.isPresent() ? Boolean.TRUE : null;
        });
    }

    private Optional<LogicalSlotRecord> findAvailableSlot(String familyId) {
        if (familyId == null) {
            return Optional.empty();
        }

        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            for (LogicalSlotRecord slot : server.getSlots()) {
                if (SlotLifecycleStatus.AVAILABLE != slot.getStatus()) {
                    continue;
                }
                String slotFamily = slot.getMetadata().get("family");
                if (!familyId.equalsIgnoreCase(slotFamily)) {
                    continue;
                }
                int pending = pendingOccupancy.getOrDefault(slot.getSlotId(), 0);
                if (slot.getMaxPlayers() > 0 && slot.getOnlinePlayers() + pending >= slot.getMaxPlayers()) {
                    continue;
                }
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private void dispatchQueuedPlayers(String familyId, LogicalSlotRecord slot) {
        Deque<PlayerRequestContext> queue = pendingQueues.get(familyId);
        if (queue == null) {
            provisioningFamilies.remove(familyId);
            return;
        }

        while (!queue.isEmpty()) {
            int pending = pendingOccupancy.getOrDefault(slot.getSlotId(), 0);
            if (slot.getMaxPlayers() > 0 && slot.getOnlinePlayers() + pending >= slot.getMaxPlayers()) {
                break;
            }

            PlayerRequestContext context = queue.pollFirst();
            if (context == null) {
                break;
            }

            if (context.hasExceededWait(MAX_QUEUE_WAIT)) {
                sendDisconnectCommand(context.request, "queue-timeout");
                continue;
            }

            routePlayer(context, slot);
            provisioningFamilies.remove(familyId);
        }

        if (queue.isEmpty()) {
            pendingQueues.remove(familyId);
            provisioningFamilies.remove(familyId);
        } else {
            triggerProvisionIfNeeded(familyId, queue.peekFirst().request.getMetadata());
        }
    }

    private void routePlayer(PlayerRequestContext context, LogicalSlotRecord slot) {
        PlayerSlotRequest request = context.request;

        requestReservation(context, slot).whenCompleteAsync((response, throwable) -> {
            if (throwable != null) {
                LOGGER.warn("Reservation request failed for player {}: {}", request.getPlayerName(), throwable.getMessage());
                retryRequest(context, "reservation-failed");
                return;
            }

            if (response == null) {
                LOGGER.warn("Reservation response missing for player {}", request.getPlayerName());
                retryRequest(context, "reservation-failed");
                return;
            }

            if (!response.isAccepted()) {
                LOGGER.warn("Reservation rejected for player {}: {}", request.getPlayerName(), response.getReason());
                retryRequest(context, response.getReason() != null ? response.getReason() : "reservation-rejected");
                return;
            }

            String reservationToken = response.getReservationToken();
            if (reservationToken == null || reservationToken.isBlank()) {
                LOGGER.warn("Reservation accepted without token for player {}", request.getPlayerName());
                retryRequest(context, "reservation-missing-token");
                return;
            }

            dispatchRouteWithReservation(context, slot, reservationToken);
        }, scheduler);
    }

    private void dispatchRouteWithReservation(PlayerRequestContext context,
                                              LogicalSlotRecord slot,
                                              String reservationToken) {
        PlayerSlotRequest request = context.request;

        PlayerRouteCommand command = new PlayerRouteCommand();
        command.setAction(PlayerRouteCommand.Action.ROUTE);
        command.setRequestId(request.getRequestId());
        command.setPlayerId(request.getPlayerId());
        command.setPlayerName(request.getPlayerName());
        command.setProxyId(request.getProxyId());
        command.setServerId(slot.getServerId());
        command.setSlotId(slot.getSlotId());
        command.setSlotSuffix(slot.getSlotSuffix());

        Map<String, String> slotMetadata = slot.getMetadata();
        command.setTargetWorld(slotMetadata.getOrDefault("targetWorld", ""));
        command.setSpawnX(parseDouble(slotMetadata.get("spawnX"), 0.5D));
        command.setSpawnY(parseDouble(slotMetadata.get("spawnY"), 64D));
        command.setSpawnZ(parseDouble(slotMetadata.get("spawnZ"), 0.5D));
        command.setSpawnYaw((float) parseDouble(slotMetadata.get("spawnYaw"), 0F));
        command.setSpawnPitch((float) parseDouble(slotMetadata.get("spawnPitch"), 0F));

        Map<String, String> metadata = new HashMap<>();
        metadata.putAll(slotMetadata);
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.putIfAbsent("family", request.getFamilyId());
        metadata.put("reservationToken", reservationToken);
        command.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
        messageBus.broadcast(ChannelConstants.getServerPlayerRouteChannel(slot.getServerId()), command);
        LOGGER.info("Routing player {} to slot {} on server {}", request.getPlayerName(), slot.getSlotId(), slot.getServerId());

        adjustPendingOccupancy(slot.getSlotId(), 1);
        ScheduledFuture<?> timeout = scheduler.schedule(() -> handleRouteTimeout(context, slot),
                ROUTE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        inFlightRoutes.put(request.getRequestId(), new InFlightRoute(context, slot.getSlotId(), timeout));
    }

    private void handleRouteTimeout(PlayerRequestContext context, LogicalSlotRecord slot) {
        InFlightRoute removed = inFlightRoutes.remove(context.request.getRequestId());
        if (removed == null) {
            return;
        }

        adjustPendingOccupancy(slot.getSlotId(), -1);
        LOGGER.warn("Player routing timed out for request {}", context.request.getRequestId());
        sendDisconnectCommand(context.request, "route-timeout");
    }

    private void handleSlotUnavailable(LogicalSlotRecord slot, String reason) {
        pendingOccupancy.remove(slot.getSlotId());

        Set<UUID> affected = new HashSet<>();
        for (Map.Entry<UUID, InFlightRoute> entry : new ArrayList<>(inFlightRoutes.entrySet())) {
            InFlightRoute route = entry.getValue();
            if (!slot.getSlotId().equals(route.slotId)) {
                continue;
            }

            inFlightRoutes.remove(entry.getKey());
            if (route.timeoutFuture != null) {
                route.timeoutFuture.cancel(false);
            }
            adjustPendingOccupancy(route.slotId, -1);
            affected.add(entry.getKey());
            retryRequest(route.request, reason);
        }

        if (!affected.isEmpty()) {
            LOGGER.warn("Slot {} became unavailable; re-queued {} pending players", slot.getSlotId(), affected.size());
        }
    }

    private void retryRequest(PlayerRequestContext context, String reason) {
        if (context == null) {
            return;
        }

        if (context.hasExceededWait(MAX_QUEUE_WAIT)) {
            sendDisconnectCommand(context.request, reason != null ? reason : "queue-timeout");
            return;
        }

        if (!context.registerRetry(MAX_ROUTE_RETRIES)) {
            sendDisconnectCommand(context.request, reason != null ? reason : "route-failed");
            return;
        }

        enqueueContext(context);
        triggerProvisionIfNeeded(context.request.getFamilyId(), context.request.getMetadata());
    }

    private boolean shouldRetry(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return RETRYABLE_FAILURES.contains(reason);
    }

    private void sendDisconnectCommand(PlayerSlotRequest request, String reason) {
        if (request == null) {
            return;
        }

        PlayerRouteCommand command = new PlayerRouteCommand();
        command.setAction(PlayerRouteCommand.Action.DISCONNECT);
        command.setRequestId(request.getRequestId());
        command.setPlayerId(request.getPlayerId());
        command.setPlayerName(request.getPlayerName());
        command.setProxyId(request.getProxyId());

        Map<String, String> metadata = new HashMap<>(request.getMetadata());
        metadata.put("reason", reason != null ? reason : "unknown");
        command.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
    }

    private void adjustPendingOccupancy(String slotId, int delta) {
        if (slotId == null) {
            return;
        }

        pendingOccupancy.compute(slotId, (id, current) -> {
            int value = (current != null ? current : 0) + delta;
            return value <= 0 ? null : value;
        });
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }

    private double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static final class PlayerRequestContext {
        final PlayerSlotRequest request;
        private final long createdAt = System.currentTimeMillis();
        private volatile long lastEnqueuedAt = createdAt;
        private int retries;

        PlayerRequestContext(PlayerSlotRequest request) {
            this.request = request;
        }

        void markEnqueued() {
            lastEnqueuedAt = System.currentTimeMillis();
        }

        boolean hasExceededWait(Duration threshold) {
            return System.currentTimeMillis() - createdAt >= threshold.toMillis();
        }

        boolean registerRetry(int maxRetries) {
            retries++;
            return retries <= maxRetries;
        }
    }

    private record InFlightRoute(PlayerRequestContext request, String slotId, ScheduledFuture<?> timeoutFuture) {
    }
}
