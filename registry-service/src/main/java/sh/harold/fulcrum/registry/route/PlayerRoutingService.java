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
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterCreatedMessage;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterEndedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationCreatedMessage;
import sh.harold.fulcrum.api.party.PartyConstants;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;
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
import java.util.stream.Collectors;

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
    private final ConcurrentMap<String, PartyReservationAllocation> activePartyReservations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<PartyReservationSnapshot>> pendingPartyReservations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Queue<PlayerRequestContext>> pendingPartyPlayerRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MatchRosterSnapshot> matchRosters = new ConcurrentHashMap<>();

    private final MessageHandler playerRequestHandler = this::handlePlayerRequest;
    private final MessageHandler slotStatusHandler = this::handleSlotStatus;
    private final MessageHandler routeAckHandler = this::handleRouteAck;
    private final MessageHandler reservationResponseHandler = this::handleReservationResponse;
    private final MessageHandler partyReservationHandler = this::handlePartyReservationCreated;
    private final MessageHandler partyReservationClaimedHandler = this::handlePartyReservationClaimed;
    private final MessageHandler matchRosterHandler = this::handleMatchRosterCreated;
    private final MessageHandler matchRosterEndedHandler = this::handleMatchRosterEnded;

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
        messageBus.subscribe(ChannelConstants.PARTY_RESERVATION_CREATED, partyReservationHandler);
        messageBus.subscribe(ChannelConstants.MATCH_ROSTER_CREATED, matchRosterHandler);
        messageBus.subscribe(ChannelConstants.MATCH_ROSTER_ENDED, matchRosterEndedHandler);
        messageBus.subscribe(ChannelConstants.PARTY_RESERVATION_CLAIMED, partyReservationClaimedHandler);

        seedAvailableSlots();
        LOGGER.info("PlayerRoutingService subscribed to matchmaking channels");
    }

    public void shutdown() {
        scheduler.shutdownNow();
        messageBus.unsubscribe(ChannelConstants.REGISTRY_PLAYER_REQUEST, playerRequestHandler);
        messageBus.unsubscribe(ChannelConstants.REGISTRY_SLOT_STATUS, slotStatusHandler);
        messageBus.unsubscribe(ChannelConstants.PLAYER_ROUTE_ACK, routeAckHandler);
        messageBus.unsubscribe(ChannelConstants.PLAYER_RESERVATION_RESPONSE, reservationResponseHandler);
        messageBus.unsubscribe(ChannelConstants.PARTY_RESERVATION_CREATED, partyReservationHandler);
        messageBus.unsubscribe(ChannelConstants.MATCH_ROSTER_CREATED, matchRosterHandler);
        messageBus.unsubscribe(ChannelConstants.MATCH_ROSTER_ENDED, matchRosterEndedHandler);
        messageBus.unsubscribe(ChannelConstants.PARTY_RESERVATION_CLAIMED, partyReservationClaimedHandler);
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
            PlayerSlotRequest request = convert(envelope.payload(), PlayerSlotRequest.class);
            request.validate();

            Map<String, String> requestMetadata = request.getMetadata();
            String reservationId = requestMetadata != null ? requestMetadata.get("partyReservationId") : null;
            if (reservationId != null && !reservationId.isBlank()) {
                handlePartyPlayerRequest(request, reservationId);
                return;
            }

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

    private void handlePartyPlayerRequest(PlayerSlotRequest request, String reservationId) {
        PartyReservationAllocation allocation = activePartyReservations.get(reservationId);
        PlayerRequestContext context = new PlayerRequestContext(request);

        if (allocation == null || allocation.isReleased()) {
            Queue<PlayerRequestContext> queue = pendingPartyPlayerRequests.computeIfAbsent(reservationId, key -> new ConcurrentLinkedQueue<>());
            context.markEnqueued();
            queue.add(context);
            return;
        }

        PartyReservationToken token = allocation.getTokenForPlayer(request.getPlayerId());
        if (token == null) {
            LOGGER.warn("No reservation token for player {} in reservation {}", request.getPlayerName(), reservationId);
            sendDisconnectCommand(request, "party-token-missing");
            return;
        }

        Map<String, String> metadata = request.getMetadata();
        String providedToken = metadata != null ? metadata.get("partyTokenId") : null;
        if (providedToken != null && !providedToken.equals(token.getTokenId())) {
            LOGGER.warn("Reservation token mismatch for player {} in reservation {}", request.getPlayerName(), reservationId);
            sendDisconnectCommand(request, "party-token-mismatch");
            return;
        }

        RegisteredServerData server = serverRegistry.getServer(allocation.serverId);
        if (server == null) {
            LOGGER.warn("Assigned server {} for reservation {} no longer available", allocation.serverId, reservationId);
            Queue<PlayerRequestContext> queue = pendingPartyPlayerRequests.computeIfAbsent(reservationId, key -> new ConcurrentLinkedQueue<>());
            context.markEnqueued();
            queue.add(context);
            requeuePartyReservation(allocation);
            return;
        }

        LogicalSlotRecord slot = server.getSlot(allocation.slotSuffix);
        if (slot == null || SlotLifecycleStatus.AVAILABLE != slot.getStatus()) {
            LOGGER.warn("Assigned slot {} for reservation {} unavailable; re-queuing", allocation.slotId, reservationId);
            Queue<PlayerRequestContext> queue = pendingPartyPlayerRequests.computeIfAbsent(reservationId, key -> new ConcurrentLinkedQueue<>());
            context.markEnqueued();
            queue.add(context);
            requeuePartyReservation(allocation);
            return;
        }

        if (!allocation.markDispatched(request.getPlayerId())) {
            LOGGER.debug("Player {} already dispatched for reservation {}", request.getPlayerName(), reservationId);
            return;
        }

        dispatchRouteWithReservation(context, slot, token.getTokenId(), true);
    }

    private void handleSlotStatus(MessageEnvelope envelope) {
        try {
            SlotStatusUpdateMessage update = convert(envelope.payload(), SlotStatusUpdateMessage.class);
            LogicalSlotRecord slot = serverRegistry.updateSlot(update.getServerId(), update);
            if (slot == null) {
                return;
            }

            SlotLifecycleStatus status = slot.getStatus();
            String familyId = slot.getMetadata().get("family");
            if (status == SlotLifecycleStatus.AVAILABLE) {
                matchRosters.remove(slot.getSlotId());
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

    private void handlePartyReservationCreated(MessageEnvelope envelope) {
        try {
            PartyReservationCreatedMessage message = convert(envelope.payload(), PartyReservationCreatedMessage.class);
            if (message == null) {
                return;
            }

            PartyReservationSnapshot reservation = message.getReservation();
            if (reservation == null) {
                LOGGER.warn("Received PartyReservationCreatedMessage without snapshot");
                return;
            }

            String reservationId = reservation.getReservationId();
            if (reservationId == null || reservationId.isBlank()) {
                LOGGER.warn("Party reservation snapshot missing reservationId");
                return;
            }

            if (activePartyReservations.containsKey(reservationId)) {
                LOGGER.debug("Reservation {} already active; ignoring duplicate message", reservationId);
                return;
            }

            String familyId = message.getFamilyId();
            if (familyId == null || familyId.isBlank()) {
                LOGGER.warn("Party reservation {} missing family id", reservationId);
                return;
            }
            String variantId = message.getVariantId();

            Map<UUID, PartyReservationToken> tokens = reservation.getTokens();
            int partySize = tokens != null ? tokens.size() : 0;
            if (partySize <= 0) {
                LOGGER.warn("Party reservation {} has no participants", reservationId);
                return;
            }

            String targetServerId = reservation.getTargetServerId();
            if (targetServerId != null && !targetServerId.isBlank()) {
                RegisteredServerData targetServer = serverRegistry.getServer(targetServerId);
                if (targetServer == null) {
                    LOGGER.warn("Target server {} not found for party reservation {}", targetServerId, reservationId);
                } else {
                    LogicalSlotRecord targetSlot = findSlotOnServer(targetServer, familyId, variantId, partySize);
                    if (targetSlot != null) {
                        allocatePartyReservation(reservation, targetSlot, familyId, variantId);
                        return;
                    }
                    LOGGER.info("Target server {} cannot satisfy reservation {}; falling back to family queue",
                            targetServerId, reservationId);
                }
            }

            Optional<LogicalSlotRecord> slotOpt = findAvailableSlotForParty(familyId, variantId, partySize);
            if (slotOpt.isPresent()) {
                allocatePartyReservation(reservation, slotOpt.get(), familyId, variantId);
            } else {
                LOGGER.info("Queuing party reservation {} for family {} (variant {})", reservationId, familyId, variantId);
                pendingPartyReservations.computeIfAbsent(familyId, key -> new ConcurrentLinkedDeque<>()).addLast(reservation);
                Map<String, String> provisionMetadata = new HashMap<>();
                provisionMetadata.put("partyReservationId", reservationId);
                if (variantId != null && !variantId.isBlank()) {
                    provisionMetadata.put("variant", variantId);
                }
                provisionMetadata.put("partySize", Integer.toString(partySize));
                triggerProvisionIfNeeded(familyId, provisionMetadata);
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle party reservation message", exception);
        }
    }

    private void handleMatchRosterCreated(MessageEnvelope envelope) {
        try {
            MatchRosterCreatedMessage message = convert(envelope.payload(), MatchRosterCreatedMessage.class);
            if (message == null) {
                return;
            }
            String slotId = message.getSlotId();
            if (slotId == null || slotId.isBlank()) {
                return;
            }
            Set<UUID> players = message.getPlayers();
            if (players == null || players.isEmpty()) {
                matchRosters.remove(slotId);
                return;
            }
            matchRosters.put(slotId, new MatchRosterSnapshot(message.getMatchId(), Set.copyOf(players), System.currentTimeMillis()));
        } catch (Exception exception) {
            LOGGER.error("Failed to handle match roster message", exception);
        }
    }

    private void handleMatchRosterEnded(MessageEnvelope envelope) {
        try {
            MatchRosterEndedMessage message = convert(envelope.payload(), MatchRosterEndedMessage.class);
            if (message == null) {
                return;
            }
            String slotId = message.getSlotId();
            if (slotId == null || slotId.isBlank()) {
                return;
            }
            matchRosters.remove(slotId);
        } catch (Exception exception) {
            LOGGER.error("Failed to handle match roster end message", exception);
        }
    }

    private void handleRouteAck(MessageEnvelope envelope) {
        try {
            PlayerRouteAck ack = convert(envelope.payload(), PlayerRouteAck.class);
            ack.validate();

            InFlightRoute route = inFlightRoutes.remove(ack.getRequestId());
            if (route != null) {
                if (route.timeoutFuture != null) {
                    route.timeoutFuture.cancel(false);
                }
                adjustPendingOccupancy(route.slotId, -1);
                PlayerSlotRequest routedRequest = route.request.request;
                Map<String, String> metadata = routedRequest.getMetadata();
                if (metadata != null) {
                    String reservationId = metadata.get("partyReservationId");
                    if (reservationId != null && !reservationId.isBlank()) {
                        handlePartyRouteAck(reservationId, routedRequest.getPlayerId());
                    }
                }
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
            PlayerReservationResponse response = convert(envelope.payload(), PlayerReservationResponse.class);
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

    private Optional<LogicalSlotRecord> findAvailableSlotForParty(String familyId, String variantId, int partySize) {
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
                if (!variantMatches(slot, variantId)) {
                    continue;
                }
                if (!canSlotFitParty(slot, partySize)) {
                    continue;
                }
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private LogicalSlotRecord findSlotOnServer(RegisteredServerData server,
                                               String familyId,
                                               String variantId,
                                               int partySize) {
        if (server == null) {
            return null;
        }
        for (LogicalSlotRecord slot : server.getSlots()) {
            if (SlotLifecycleStatus.AVAILABLE != slot.getStatus()) {
                continue;
            }
            String slotFamily = slot.getMetadata().get("family");
            if (familyId != null && (!familyId.equalsIgnoreCase(slotFamily))) {
                continue;
            }
            if (!variantMatches(slot, variantId)) {
                continue;
            }
            if (!canSlotFitParty(slot, partySize)) {
                continue;
            }
            return slot;
        }
        return null;
    }

    private void dispatchQueuedPlayers(String familyId, LogicalSlotRecord slot) {
        if (familyId == null || slot == null) {
            return;
        }

        processPendingPartyReservations(familyId, slot);

        if (slotCapacityRemaining(slot) <= 0) {
            provisioningFamilies.remove(familyId);
            return;
        }

        Deque<PlayerRequestContext> queue = pendingQueues.get(familyId);
        if (queue == null) {
            provisioningFamilies.remove(familyId);
            return;
        }

        while (!queue.isEmpty()) {
            if (slotCapacityRemaining(slot) <= 0) {
                break;
            }
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

            dispatchRouteWithReservation(context, slot, reservationToken, false);
        }, scheduler);
    }

    private void dispatchRouteWithReservation(PlayerRequestContext context,
                                              LogicalSlotRecord slot,
                                              String reservationToken,
                                              boolean preReserved) {
        PlayerSlotRequest request = context.request;

        MatchRosterSnapshot rosterSnapshot = matchRosters.get(slot.getSlotId());
        if (rosterSnapshot != null) {
            Set<UUID> allowedPlayers = rosterSnapshot.players();
            if (allowedPlayers != null && !allowedPlayers.contains(request.getPlayerId())) {
                LOGGER.warn("Blocking route for player {} into slot {} due to roster lock", request.getPlayerName(), slot.getSlotId());
                sendDisconnectCommand(request, "match-roster-locked");
                return;
            }
        }

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
        String reservationId = metadata.get("partyReservationId");
        if (reservationId != null) {
            PartyReservationAllocation allocation = activePartyReservations.get(reservationId);
            if (allocation != null) {
                if (allocation.teamIndex >= 0) {
                    metadata.put("team.index", Integer.toString(allocation.teamIndex));
                }
                if (allocation.snapshot != null && allocation.snapshot.getPartyId() != null) {
                    metadata.putIfAbsent("partyId", allocation.snapshot.getPartyId().toString());
                }
            }
        }
        command.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
        messageBus.broadcast(ChannelConstants.getServerPlayerRouteChannel(slot.getServerId()), command);
        LOGGER.info("Routing player {} to slot {} on server {}", request.getPlayerName(), slot.getSlotId(), slot.getServerId());

        if (!preReserved) {
            adjustPendingOccupancy(slot.getSlotId(), 1);
        }
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
        matchRosters.remove(slot.getSlotId());

        for (PartyReservationAllocation allocation : new ArrayList<>(activePartyReservations.values())) {
            if (allocation.slotId.equals(slot.getSlotId())) {
                LOGGER.warn("Re-queueing party reservation {} after slot {} became unavailable", allocation.reservationId, slot.getSlotId());
                requeuePartyReservation(allocation);
            }
        }

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

    private void processPendingPartyReservations(String familyId, LogicalSlotRecord slot) {
        Deque<PartyReservationSnapshot> queue = pendingPartyReservations.get(familyId);
        if (queue == null) {
            return;
        }

        Iterator<PartyReservationSnapshot> iterator = queue.iterator();
        while (iterator.hasNext()) {
            PartyReservationSnapshot reservation = iterator.next();
            Map<UUID, PartyReservationToken> tokens = reservation.getTokens();
            int partySize = tokens != null ? tokens.size() : 0;
            if (partySize <= 0) {
                iterator.remove();
                continue;
            }
            String variantId = reservation.getVariantId();
            if (!variantMatches(slot, variantId)) {
                continue;
            }
            if (!canSlotFitParty(slot, partySize)) {
                continue;
            }
            iterator.remove();
            allocatePartyReservation(reservation, slot, familyId, variantId);
            break;
        }

        if (queue.isEmpty()) {
            pendingPartyReservations.remove(familyId);
        }
    }

    private void allocatePartyReservation(PartyReservationSnapshot reservation,
                                          LogicalSlotRecord slot,
                                          String familyId,
                                          String variantId) {
        if (reservation == null) {
            return;
        }

        PartyReservationAllocation existing = activePartyReservations.get(reservation.getReservationId());
        if (existing != null && !existing.isReleased()) {
            LOGGER.debug("Reservation {} already allocated to slot {}", reservation.getReservationId(), existing.slotId);
            return;
        }

        int teamCount = resolveTeamCount(slot);
        int teamIndex = nextAvailableTeamIndex(slot, teamCount);
        if (teamCount > 0 && teamIndex < 0) {
            LOGGER.warn("Unable to assign party {} to slot {} because all {} teams are occupied",
                    reservation.getReservationId(), slot.getSlotId(), teamCount);
            reservation.setTargetServerId(null);
            reservation.setAssignedTeamIndex(null);
            pendingPartyReservations.computeIfAbsent(familyId, key -> new ConcurrentLinkedDeque<>())
                    .addFirst(reservation);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("partyReservationId", reservation.getReservationId());
            if (variantId != null && !variantId.isBlank()) {
                metadata.put("variant", variantId);
            }
            metadata.put("partySize", Integer.toString(reservation.getTokens() != null ? reservation.getTokens().size() : 0));
            triggerProvisionIfNeeded(familyId, metadata);
            return;
        }

        reservation.setTargetServerId(slot.getServerId());
        reservation.setAssignedTeamIndex(teamIndex >= 0 ? teamIndex : null);
        PartyReservationAllocation allocation = new PartyReservationAllocation(reservation, slot, familyId, variantId, teamIndex);
        activePartyReservations.put(reservation.getReservationId(), allocation);
        adjustPendingOccupancy(slot.getSlotId(), allocation.partySize);
        LOGGER.info("Allocated party reservation {} to slot {} on server {}",
                reservation.getReservationId(), slot.getSlotId(), slot.getServerId());
        processPendingPartyPlayerContexts(reservation.getReservationId(), allocation);
    }

    private void processPendingPartyPlayerContexts(String reservationId, PartyReservationAllocation allocation) {
        Queue<PlayerRequestContext> queue = pendingPartyPlayerRequests.remove(reservationId);
        if (queue == null || allocation == null) {
            return;
        }
        PlayerRequestContext context;
        while ((context = queue.poll()) != null) {
            handlePartyPlayerRequest(context.request, reservationId);
        }
    }

    private void requeuePartyReservation(PartyReservationAllocation allocation) {
        if (allocation == null || allocation.isReleased()) {
            return;
        }

        activePartyReservations.remove(allocation.reservationId, allocation);
        allocation.release();
        adjustPendingOccupancy(allocation.slotId, -allocation.partySize);
        allocation.snapshot.setTargetServerId(null);
        allocation.snapshot.setAssignedTeamIndex(null);
        pendingPartyReservations.computeIfAbsent(allocation.familyId, key -> new ConcurrentLinkedDeque<>())
                .addFirst(allocation.snapshot);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("partyReservationId", allocation.reservationId);
        if (allocation.variantId != null && !allocation.variantId.isBlank()) {
            metadata.put("variant", allocation.variantId);
        }
        metadata.put("partySize", Integer.toString(allocation.partySize));
        triggerProvisionIfNeeded(allocation.familyId, metadata);
    }

    private void handlePartyRouteAck(String reservationId, UUID playerId) {
        PartyReservationAllocation allocation = activePartyReservations.get(reservationId);
        if (allocation == null) {
            return;
        }

        boolean completed = allocation.onRouteCompleted(playerId);
        if (completed) {
            LOGGER.debug("Party reservation {} fully routed", reservationId);
            releasePartyReservation(reservationId, allocation, true, "route-ack", Map.of(), Set.of());
        }
    }

    private void handlePartyReservationClaimed(MessageEnvelope envelope) {
        try {
            PartyReservationClaimedMessage message = convert(envelope.payload(), PartyReservationClaimedMessage.class);
            if (message == null) {
                return;
            }
            String reservationId = message.getReservationId();
            if (reservationId == null || reservationId.isBlank()) {
                return;
            }

            PartyReservationAllocation allocation = activePartyReservations.get(reservationId);
            if (allocation == null) {
                return;
            }

            PartyReservationAllocation.ClaimProgress progress = allocation.recordClaim(
                    message.getPlayerId(),
                    message.isSuccess(),
                    message.getReason());
            if (!message.isSuccess()) {
                LOGGER.warn("Reservation {} claim failed for player {} (reason: {})",
                        reservationId, message.getPlayerId(), message.getReason());
            }
            if (progress.complete()) {
                releasePartyReservation(reservationId, allocation, progress.success(), "claim",
                        progress.failures(), progress.missingPlayers());
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle party reservation claim message", exception);
        }
    }

    private int slotCapacityRemaining(LogicalSlotRecord slot) {
        if (slot == null) {
            return 0;
        }
        int max = slot.getMaxPlayers();
        if (max <= 0) {
            return Integer.MAX_VALUE;
        }
        int pending = pendingOccupancy.getOrDefault(slot.getSlotId(), 0);
        return max - slot.getOnlinePlayers() - pending;
    }

    private boolean variantMatches(LogicalSlotRecord slot, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return true;
        }
        Map<String, String> metadata = slot.getMetadata();
        String slotVariant = metadata.get("variant");
        if (variantId.equalsIgnoreCase(slotVariant)) {
            return true;
        }
        String slotGameType = slot.getGameType();
        if (variantId.equalsIgnoreCase(slotGameType)) {
            return true;
        }
        String metaVariant = metadata.get("familyVariant");
        return variantId.equalsIgnoreCase(metaVariant);
    }

    private boolean canSlotFitParty(LogicalSlotRecord slot, int partySize) {
        if (slotCapacityRemaining(slot) < partySize) {
            return false;
        }
        int maxTeamSize = parsePositiveInt(slot.getMetadata(), "team.max");
        if (maxTeamSize > 0 && partySize > maxTeamSize) {
            return false;
        }
        int teamCount = resolveTeamCount(slot);
        if (teamCount > 0) {
            long existingParties = activePartyReservations.values().stream()
                    .filter(allocation -> allocation != null
                            && allocation.slotId.equals(slot.getSlotId())
                            && !allocation.isReleased()
                            && allocation.teamIndex >= 0)
                    .count();
            return existingParties < teamCount;
        }
        return true;
    }

    private int parsePositiveInt(Map<String, String> metadata, String key) {
        if (metadata == null) {
            return -1;
        }
        String raw = metadata.get(key);
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    private int resolveTeamCount(LogicalSlotRecord slot) {
        Map<String, String> metadata = slot.getMetadata();
        int teamCount = parsePositiveInt(metadata, "team.count");
        if (teamCount > 0) {
            return teamCount;
        }
        int teamSize = parsePositiveInt(metadata, "team.max");
        int maxPlayers = slot.getMaxPlayers();
        if (teamSize > 0 && maxPlayers > 0) {
            return Math.max(1, maxPlayers / Math.max(1, teamSize));
        }
        return teamSize > 0 ? Math.max(1, PartyConstants.HARD_SIZE_CAP / teamSize) : -1;
    }

    private int nextAvailableTeamIndex(LogicalSlotRecord slot, int teamCount) {
        if (teamCount <= 0) {
            return -1;
        }
        Set<Integer> used = activePartyReservations.values().stream()
                .filter(allocation -> allocation != null
                        && allocation.slotId.equals(slot.getSlotId())
                        && allocation.teamIndex >= 0)
                .map(allocation -> allocation.teamIndex)
                .collect(Collectors.toSet());
        for (int index = 0; index < teamCount; index++) {
            if (!used.contains(index)) {
                return index;
            }
        }
        return -1;
    }

    private <T> T convert(Object payload, Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        return objectMapper.convertValue(payload, type);
    }

    private void releasePartyReservation(String reservationId,
                                         PartyReservationAllocation allocation,
                                         boolean success,
                                         String context,
                                         Map<UUID, String> failures,
                                         Set<UUID> missingPlayers) {
        if (allocation == null) {
            return;
        }
        boolean removed = activePartyReservations.remove(reservationId, allocation);
        if (removed) {
            adjustPendingOccupancy(allocation.slotId, -allocation.partySize);
        }
        allocation.release();
        pendingPartyPlayerRequests.remove(reservationId);

        if (success) {
            LOGGER.info("Party reservation {} completed (context: {}) on server {}", reservationId, context, allocation.serverId);
        } else {
            LOGGER.warn("Party reservation {} released (context: {}) failures={} missing={}",
                    reservationId, context, failures, missingPlayers);
        }
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

    private static final class PartyReservationAllocation {
        final PartyReservationSnapshot snapshot;
        final String reservationId;
        final String familyId;
        final String variantId;
        final String slotId;
        final String slotSuffix;
        final String serverId;
        final Map<UUID, PartyReservationToken> tokensByPlayer;
        final Map<String, UUID> playerByToken;
        final Set<UUID> dispatchedPlayers = ConcurrentHashMap.newKeySet();
        final Set<UUID> claimedPlayers = ConcurrentHashMap.newKeySet();
        final ConcurrentMap<UUID, String> claimFailures = new ConcurrentHashMap<>();
        final int partySize;
        final int teamIndex;
        private volatile boolean released;

        PartyReservationAllocation(PartyReservationSnapshot snapshot,
                                   LogicalSlotRecord slot,
                                   String familyId,
                                   String variantId,
                                   int teamIndex) {
            this.snapshot = snapshot;
            this.reservationId = snapshot.getReservationId();
            this.familyId = familyId;
            this.variantId = variantId;
            this.slotId = slot.getSlotId();
            this.slotSuffix = slot.getSlotSuffix();
            this.serverId = slot.getServerId();
            Map<UUID, PartyReservationToken> tokens = snapshot.getTokens();
            this.tokensByPlayer = tokens != null ? new LinkedHashMap<>(tokens) : Collections.emptyMap();
            this.playerByToken = new HashMap<>();
            this.tokensByPlayer.forEach((playerId, token) -> playerByToken.put(token.getTokenId(), playerId));
            this.partySize = this.tokensByPlayer.size();
            this.teamIndex = teamIndex;
            this.released = false;
        }

        PartyReservationToken getTokenForPlayer(UUID playerId) {
            return tokensByPlayer.get(playerId);
        }

        boolean markDispatched(UUID playerId) {
            return dispatchedPlayers.add(playerId);
        }

        boolean onRouteCompleted(UUID playerId) {
            dispatchedPlayers.add(playerId);
            if (dispatchedPlayers.size() >= partySize) {
                released = true;
                return true;
            }
            return false;
        }

        ClaimProgress recordClaim(UUID playerId, boolean success, String reason) {
            if (playerId != null) {
                if (success) {
                    claimedPlayers.add(playerId);
                    claimFailures.remove(playerId);
                } else {
                    claimedPlayers.remove(playerId);
                    claimFailures.put(playerId, reason != null ? reason : "unknown");
                }
            }

            Set<UUID> expected = new HashSet<>(tokensByPlayer.keySet());
            expected.removeAll(claimedPlayers);
            expected.removeAll(claimFailures.keySet());

            int processed = claimedPlayers.size() + claimFailures.size();
            boolean complete = partySize > 0 && processed >= partySize;
            boolean successful = complete && claimFailures.isEmpty() && claimedPlayers.size() >= partySize;

            return new ClaimProgress(
                    complete,
                    successful,
                    Map.copyOf(claimFailures),
                    Set.copyOf(expected)
            );
        }

        boolean isReleased() {
            return released;
        }

        void release() {
            released = true;
        }

        private record ClaimProgress(boolean complete, boolean success, Map<UUID, String> failures, Set<UUID> missing) {

            Set<UUID> missingPlayers() {
                        return missing;
                    }
                }
    }

    private record MatchRosterSnapshot(UUID matchId, Set<UUID> players, long updatedAt) {
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
