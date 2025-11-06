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
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.route.model.BlockedSlotContext;
import sh.harold.fulcrum.registry.route.model.PartyReservationAllocation;
import sh.harold.fulcrum.registry.route.model.PlayerRequestContext;
import sh.harold.fulcrum.registry.route.service.ActivePlayerTracker;
import sh.harold.fulcrum.registry.route.service.MatchRosterService;
import sh.harold.fulcrum.registry.route.service.PartyReservationCoordinator;
import sh.harold.fulcrum.registry.route.store.RedisRoutingStore;
import sh.harold.fulcrum.registry.route.util.SlotIdUtils;
import sh.harold.fulcrum.registry.route.util.SlotSelectionRules;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.shutdown.ShutdownIntentManager;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;
import sh.harold.fulcrum.registry.slot.SlotProvisionService;
import sh.harold.fulcrum.registry.slot.SlotProvisionService.ProvisionResult;
import sh.harold.fulcrum.registry.slot.store.RedisSlotStore;

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
    private static final Duration RECENT_SLOT_TTL = Duration.ofSeconds(45);
    private static final int MAX_ROUTE_RETRIES = 3;
    private static final String CURRENT_SLOT_METADATA_KEY = "currentSlotId";
    private static final String PREVIOUS_SLOT_METADATA_KEY = "previousSlotId";
    private static final int RECENT_SLOT_HISTORY = 3;

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
    private final RedisSlotStore slotStore;
    private final RedisRoutingStore routingStore;
    private final ActivePlayerTracker activePlayerTracker;
    private final MatchRosterService matchRosterService;
    private final PartyReservationCoordinator partyCoordinator;
    private final ShutdownIntentManager shutdownIntentManager;

    private final ConcurrentMap<UUID, ScheduledFuture<?>> routeTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CompletableFuture<PlayerReservationResponse>> pendingReservations = new ConcurrentHashMap<>();

    private final MessageHandler playerRequestHandler = this::handlePlayerRequest;
    private final MessageHandler slotStatusHandler = this::handleSlotStatus;
    private final MessageHandler routeAckHandler = this::handleRouteAck;
    private final MessageHandler reservationResponseHandler = this::handleReservationResponse;
    private final MessageHandler partyReservationHandler = this::handlePartyReservationCreated;
    private final MessageHandler partyReservationClaimedHandler = this::handlePartyReservationClaimed;
    private final MessageHandler matchRosterHandler = this::handleMatchRosterCreated;
    private final MessageHandler matchRosterEndedHandler = this::handleMatchRosterEnded;
    private final MessageHandler environmentRouteHandler = this::handleEnvironmentRouteRequest;

    public PlayerRoutingService(MessageBus messageBus,
                                SlotProvisionService slotProvisionService,
                                ServerRegistry serverRegistry,
                                ProxyRegistry proxyRegistry,
                                RedisSlotStore slotStore,
                                RedisRoutingStore routingStore,
                                ShutdownIntentManager shutdownIntentManager) {
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
        this.slotStore = slotStore;
        this.routingStore = Objects.requireNonNull(routingStore, "routingStore");
        this.activePlayerTracker = new ActivePlayerTracker(this.routingStore, RECENT_SLOT_TTL, RECENT_SLOT_HISTORY);
        this.matchRosterService = new MatchRosterService(this.routingStore, this.activePlayerTracker);
        this.partyCoordinator = new PartyReservationCoordinator(
                this.routingStore,
                this.slotProvisionService,
                this.serverRegistry,
                new PartyReservationCoordinator.Callbacks() {
                    @Override
                    public void dispatchWithReservation(PlayerRequestContext context,
                                                        LogicalSlotRecord slot,
                                                        String reservationToken,
                                                        boolean preReserved) {
                        dispatchRouteWithReservation(context, slot, reservationToken, preReserved);
                    }

                    @Override
                    public void sendDisconnect(PlayerSlotRequest request, String reason) {
                        sendDisconnectCommand(request, reason);
                    }

                    @Override
                    public void triggerProvision(String familyId, Map<String, String> metadata) {
                        triggerProvisionIfNeeded(familyId, metadata);
                    }

                    @Override
                    public void enqueueContext(PlayerRequestContext context) {
                        PlayerRoutingService.this.enqueueContext(context);
                    }

                    @Override
                    public void retryRequest(PlayerRequestContext context, String reason) {
                        PlayerRoutingService.this.retryRequest(context, reason);
                    }
                }
        );
        this.shutdownIntentManager = shutdownIntentManager;
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
        messageBus.subscribe(ChannelConstants.REGISTRY_ENVIRONMENT_ROUTE_REQUEST, environmentRouteHandler);

        seedAvailableSlots();
        LOGGER.info("PlayerRoutingService subscribed to matchmaking channels");
    }

    private static String sanitizeSlotId(String slotId) {
        return SlotIdUtils.sanitize(slotId);
    }

    private void seedAvailableSlots() {
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            if (shutdownIntentManager != null && shutdownIntentManager.isServerEvacuating(server.getServerId())) {
                continue;
            }
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

    private static String normalizeSlotId(String slotId) {
        return SlotIdUtils.normalize(slotId);
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
        messageBus.unsubscribe(ChannelConstants.REGISTRY_ENVIRONMENT_ROUTE_REQUEST, environmentRouteHandler);
        routeTimeouts.values().forEach(future -> future.cancel(false));
        routeTimeouts.clear();
        LOGGER.info("PlayerRoutingService shut down");
    }

    private void handleSlotStatus(MessageEnvelope envelope) {
        try {
            SlotStatusUpdateMessage update = convert(envelope.payload(), SlotStatusUpdateMessage.class);
            LogicalSlotRecord slot = serverRegistry.updateSlot(update.getServerId(), update);
            if (slot == null) {
                return;
            }
            if (shutdownIntentManager != null && shutdownIntentManager.isServerEvacuating(slot.getServerId())) {
                return;
            }

            SlotLifecycleStatus status = slot.getStatus();
            String familyId = slot.getMetadata().get("family");
            if (status == SlotLifecycleStatus.AVAILABLE) {
                matchRosterService.clearForSlot(slot.getSlotId());
                if (familyId != null) {
                    dispatchQueuedPlayers(familyId, slot);
                }
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
            partyCoordinator.handleReservationCreated(message);
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
            matchRosterService.handleCreated(message);
        } catch (Exception exception) {
            LOGGER.error("Failed to handle match roster message", exception);
        }
    }

    private void handlePlayerRequest(MessageEnvelope envelope) {
        try {
            PlayerSlotRequest request = convert(envelope.payload(), PlayerSlotRequest.class);
            request.validate();

            if (!processShutdownTicketIfRequired(request)) {
                return;
            }

            Map<String, String> requestMetadata = request.getMetadata();
            String reservationId = requestMetadata != null ? requestMetadata.get("partyReservationId") : null;
            if (reservationId != null && !reservationId.isBlank()) {
                BlockedSlotContext blockedSlotContext = resolveBlockedSlotContext(request);
                PlayerRequestContext context = new PlayerRequestContext(
                        request,
                        blockedSlotContext,
                        resolveVariantId(request),
                        null,
                        false
                );
                if (partyCoordinator.handlePartyPlayerRequest(context, reservationId)) {
                    return;
                }
            }

            RegisteredProxyData proxy = proxyRegistry.getProxy(request.getProxyId());
            if (proxy == null) {
                LOGGER.warn("Received player slot request for unknown proxy {}", request.getProxyId());
                sendDisconnectCommand(request, "unknown-proxy");
                return;
            }

            BlockedSlotContext blockedSlotContext = resolveBlockedSlotContext(request);
            String preferredSlotId = requestMetadata != null ? sanitizeSlotId(requestMetadata.get("rejoinSlotId")) : null;
            boolean rejoinRequest = preferredSlotId != null;
            PlayerRequestContext context = new PlayerRequestContext(
                    request,
                    blockedSlotContext,
                    resolveVariantId(request),
                    preferredSlotId,
                    rejoinRequest);

            if (context.isRejoin()) {
                Optional<LogicalSlotRecord> rejoinSlot = findSlotById(context.preferredSlotId());
                if (rejoinSlot.isPresent() && isSlotEligibleForRejoin(rejoinSlot.get())) {
                    routePlayer(context, rejoinSlot.get());
                } else {
                    LOGGER.debug("Rejoin request for player {} rejected; slot {} unavailable",
                            request.getPlayerName(), context.preferredSlotId());
                    sendRejoinUnavailableAck(request);
                }
                return;
            }
            Optional<LogicalSlotRecord> available = findAvailableSlot(
                    request.getFamilyId(),
                    context.variantId(),
                    context.blockedSlots());
            if (available.isPresent()) {
                routePlayer(context, available.get());
                return;
            }

            enqueueContext(context);
            triggerProvisionIfNeeded(request.getFamilyId(), request.getMetadata());
        } catch (Exception exception) {
            LOGGER.error("Failed to handle PlayerSlotRequest", exception);
        }
    }

    private void handleMatchRosterEnded(MessageEnvelope envelope) {
        try {
            MatchRosterEndedMessage message = convert(envelope.payload(), MatchRosterEndedMessage.class);
            if (message == null) {
                return;
            }
            matchRosterService.handleEnded(message);
        } catch (Exception exception) {
            LOGGER.error("Failed to handle match roster end message", exception);
        }
    }

    private void handleEnvironmentRouteRequest(MessageEnvelope envelope) {
        try {
            EnvironmentRouteRequestMessage request = convert(envelope.payload(), EnvironmentRouteRequestMessage.class);
            request.validate();

            RegisteredProxyData proxy = proxyRegistry.getProxy(request.getProxyId());
            if (proxy == null) {
                LOGGER.warn("Dropping environment route for {} - proxy {} is unknown",
                        request.getPlayerName(), request.getProxyId());
                return;
            }

            RegisteredServerData target = resolveEnvironmentTarget(request);
            if (target == null) {
                handleEnvironmentRouteFailure(request, "environment-unavailable");
                return;
            }

            dispatchEnvironmentRoute(request, target);
        } catch (Exception exception) {
            LOGGER.error("Failed to handle environment route request", exception);
        }
    }

    private RegisteredServerData resolveEnvironmentTarget(EnvironmentRouteRequestMessage request) {
        String explicit = request.getTargetServerId();
        if (explicit != null && !explicit.isBlank()) {
            RegisteredServerData server = serverRegistry.getServer(explicit);
            if (server == null) {
                LOGGER.warn("Environment route {} requested unknown server {}", request.getRequestId(), explicit);
                return null;
            }
            if (!matchesEnvironment(server, request.getTargetEnvironmentId())) {
                LOGGER.warn("Environment route {} rejected - server {} does not match environment {}",
                        request.getRequestId(), explicit, request.getTargetEnvironmentId());
                return null;
            }
            if (!isServerAccepting(server)) {
                LOGGER.warn("Environment route {} rejected - server {} is not accepting players", request.getRequestId(), explicit);
                return null;
            }
            return server;
        }
        return selectEnvironmentServer(request.getTargetEnvironmentId());
    }

    private void dispatchEnvironmentRoute(EnvironmentRouteRequestMessage request, RegisteredServerData target) {
        PlayerRouteCommand command = new PlayerRouteCommand();
        command.setAction(PlayerRouteCommand.Action.ROUTE);
        command.setRequestId(request.getRequestId());
        command.setPlayerId(request.getPlayerId());
        command.setPlayerName(request.getPlayerName());
        command.setProxyId(request.getProxyId());
        command.setServerId(target.getServerId());
        command.setSlotId(buildEnvironmentSlotId(target, request));
        command.setSlotSuffix("env");
        command.setTargetWorld(request.getWorldName() != null ? request.getWorldName() : "");
        command.setSpawnX(request.getSpawnX());
        command.setSpawnY(request.getSpawnY());
        command.setSpawnZ(request.getSpawnZ());
        command.setSpawnYaw(request.getSpawnYaw());
        command.setSpawnPitch(request.getSpawnPitch());

        Map<String, String> metadata = new HashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("environment", request.getTargetEnvironmentId());
        metadata.put("targetServer", target.getServerId());
        metadata.put("routeType", "environment");
        metadata.putIfAbsent("originServer", request.getOriginServerId());
        command.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
        messageBus.broadcast(ChannelConstants.getServerPlayerRouteChannel(target.getServerId()), command);
        LOGGER.info("Routing player {} to environment {} on server {}", request.getPlayerName(),
                request.getTargetEnvironmentId(), target.getServerId());
    }

    private void handleEnvironmentRouteFailure(EnvironmentRouteRequestMessage request, String reason) {
        LOGGER.warn("Environment route failed for {} (player={}): {}", request.getTargetEnvironmentId(),
                request.getPlayerName(), reason);
        if (request.getFailureMode() == EnvironmentRouteRequestMessage.FailureMode.KICK_ON_FAIL) {
            PlayerRouteCommand command = new PlayerRouteCommand();
            command.setAction(PlayerRouteCommand.Action.DISCONNECT);
            command.setRequestId(request.getRequestId());
            command.setPlayerId(request.getPlayerId());
            command.setPlayerName(request.getPlayerName());
            command.setProxyId(request.getProxyId());
            Map<String, String> metadata = new HashMap<>();
            metadata.put("reason", reason);
            metadata.put("environment", request.getTargetEnvironmentId());
            command.setMetadata(metadata);
            messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
        }
    }

    private boolean matchesEnvironment(RegisteredServerData server, String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return false;
        }
        return server.getRole() != null && server.getRole().equalsIgnoreCase(environmentId);
    }

    private boolean isServerAccepting(RegisteredServerData server) {
        RegisteredServerData.Status status = server.getStatus();
        boolean statusOk = status == RegisteredServerData.Status.RUNNING
                || status == RegisteredServerData.Status.AVAILABLE;
        if (!statusOk) {
            return false;
        }
        int max = server.getMaxCapacity();
        if (max <= 0) {
            return true;
        }
        return server.getPlayerCount() < max;
    }

    private RegisteredServerData selectEnvironmentServer(String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return null;
        }
        RegisteredServerData best = null;
        double bestScore = Double.MAX_VALUE;
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            if (!matchesEnvironment(server, environmentId)) {
                continue;
            }
            if (!isServerAccepting(server)) {
                continue;
            }
            double score = computeEnvironmentScore(server);
            if (score < bestScore) {
                best = server;
                bestScore = score;
            }
        }
        return best;
    }

    private double computeEnvironmentScore(RegisteredServerData server) {
        int max = Math.max(1, server.getMaxCapacity());
        return (double) server.getPlayerCount() / max;
    }

    private String buildEnvironmentSlotId(RegisteredServerData target, EnvironmentRouteRequestMessage request) {
        return "env:" + request.getTargetEnvironmentId() + ":" + target.getServerId();
    }

    private CompletableFuture<PlayerReservationResponse> requestReservation(PlayerRequestContext context, LogicalSlotRecord slot) {
        UUID reservationId = UUID.randomUUID();
        PlayerReservationRequest reservationRequest = new PlayerReservationRequest();
        reservationRequest.setRequestId(reservationId);
        reservationRequest.setPlayerId(context.request().getPlayerId());
        reservationRequest.setPlayerName(context.request().getPlayerName());
        reservationRequest.setProxyId(context.request().getProxyId());
        reservationRequest.setServerId(slot.getServerId());
        reservationRequest.setSlotId(slot.getSlotId());
        reservationRequest.setMetadata(context.request().getMetadata());

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

    private void clearActivePlayersForSlot(String slotId) {
        activePlayerTracker.clearActivePlayersForSlot(slotId);
    }

    private void handleRouteAck(MessageEnvelope envelope) {
        try {
            PlayerRouteAck ack = convert(envelope.payload(), PlayerRouteAck.class);
            ack.validate();

            Optional.ofNullable(routeTimeouts.remove(ack.getRequestId()))
                    .ifPresent(future -> future.cancel(false));

            Optional<RedisRoutingStore.RouteEntry> storedRoute = routingStore.removeInFlightRoute(ack.getRequestId());
            PlayerRequestContext context = storedRoute
                    .map(RedisRoutingStore.RouteEntry::getContext)
                    .map(this::fromQueueEntry)
                    .orElse(null);
            storedRoute.map(RedisRoutingStore.RouteEntry::getSlotId)
                    .filter(Objects::nonNull)
                    .ifPresent(slotId -> updatePendingOccupancy(slotId, -1));

            if (context != null) {
                Map<String, String> metadata = context.request().getMetadata();
                if (metadata != null) {
                    String reservationId = metadata.get("partyReservationId");
                    if (reservationId != null && !reservationId.isBlank()) {
                        partyCoordinator.handleRouteAck(reservationId, context.request().getPlayerId());
                    }
                }
            }

            if (ack.getStatus() == PlayerRouteAck.Status.SUCCESS) {
                if (ack.getPlayerId() != null) {
                    String sanitizedSlot = sanitizeSlotId(ack.getSlotId());
                    if (sanitizedSlot != null) {
                        UUID playerId = ack.getPlayerId();
                        activePlayerTracker.setActiveSlot(playerId, sanitizedSlot)
                                .ifPresent(previous -> activePlayerTracker.rememberRecentSlot(playerId, previous));
                    }
                }
                LOGGER.debug("Player {} successfully routed to slot {}", ack.getPlayerId(), ack.getSlotId());
                return;
            }

            LOGGER.warn("Player routing failed for request {} (reason: {})", ack.getRequestId(), ack.getReason());

            if (context == null) {
                return;
            }

            if (shouldRetry(ack.getReason())) {
                retryRequest(context, ack.getReason());
            } else {
                sendDisconnectCommand(context.request(), ack.getReason() != null ? ack.getReason() : "route-failed");
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to handle PlayerRouteAck", exception);
        }
    }

    private BlockedSlotContext resolveBlockedSlotContext(PlayerSlotRequest request) {
        if (request == null) {
            return new BlockedSlotContext(null, Collections.emptySet());
        }

        Map<String, String> metadata = request.getMetadata();
        String currentSlot = metadata != null ? sanitizeSlotId(metadata.get(CURRENT_SLOT_METADATA_KEY)) : null;
        String previousSlot = metadata != null ? sanitizeSlotId(metadata.get(PREVIOUS_SLOT_METADATA_KEY)) : null;

        Set<String> blocked = new LinkedHashSet<>();
        if (currentSlot != null) {
            blocked.add(normalizeSlotId(currentSlot));
        }
        if (previousSlot != null) {
            blocked.add(normalizeSlotId(previousSlot));
        }

        UUID playerId = request.getPlayerId();
        if (playerId != null) {
            String activeSlot = activePlayerTracker.getActiveSlot(playerId)
                    .map(SlotIdUtils::sanitize)
                    .orElse(null);
            if (activeSlot != null) {
                blocked.add(normalizeSlotId(activeSlot));
                if (currentSlot == null) {
                    currentSlot = activeSlot;
                }
            }
            blocked.addAll(activePlayerTracker.resolveRecentBlockedSlots(playerId));
        }

        return new BlockedSlotContext(currentSlot, blocked);
    }

    private String resolveVariantId(PlayerSlotRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, String> metadata = request.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        String variant = metadata.get("variant");
        if (variant != null && !variant.isBlank()) {
            return variant.trim();
        }

        String familyVariant = metadata.get("familyVariant");
        if (familyVariant != null && !familyVariant.isBlank()) {
            return familyVariant.trim();
        }

        String gameType = metadata.get("gameType");
        if (gameType != null && !gameType.isBlank()) {
            return gameType.trim();
        }

        return null;
    }

    private RedisRoutingStore.PlayerQueueEntry toQueueEntry(PlayerRequestContext context) {
        if (context == null) {
            return null;
        }
        List<String> blocked = context.blockedSlots() != null
                ? new ArrayList<>(context.blockedSlots())
                : List.of();
        return new RedisRoutingStore.PlayerQueueEntry(
                context.request(),
                context.createdAt(),
                context.lastEnqueuedAt(),
                context.currentSlotId(),
                blocked,
                context.variantId(),
                context.preferredSlotId(),
                context.isRejoin(),
                context.retries()
        );
    }

    private PlayerRequestContext fromQueueEntry(RedisRoutingStore.PlayerQueueEntry entry) {
        if (entry == null || entry.getRequest() == null) {
            return null;
        }
        Set<String> blocked = entry.getBlockedSlotIds() != null
                ? new LinkedHashSet<>(entry.getBlockedSlotIds())
                : Collections.emptySet();
        BlockedSlotContext blockedContext = new BlockedSlotContext(entry.getCurrentSlotId(), blocked);
        return new PlayerRequestContext(
                entry.getRequest(),
                blockedContext,
                entry.getVariantId(),
                entry.getPreferredSlotId(),
                entry.isRejoin(),
                entry.getCreatedAt(),
                entry.getLastEnqueuedAt(),
                entry.getRetries()
        );
    }

    private void enqueueContext(PlayerRequestContext context) {
        if (context == null) {
            return;
        }
        context.markEnqueued();
        RedisRoutingStore.PlayerQueueEntry entry = toQueueEntry(context);
        routingStore.enqueuePlayer(context.request().getFamilyId(), entry);
    }

    private boolean processShutdownTicketIfRequired(PlayerSlotRequest request) {
        if (shutdownIntentManager == null) {
            return true;
        }
        Map<String, String> metadata = request.getMetadata();
        if (metadata == null) {
            return true;
        }
        String intentId = metadata.get("shutdownIntentId");
        if (intentId == null || intentId.isBlank()) {
            return true;
        }
        Optional<ShutdownIntentManager.ShutdownTicket> ticket = shutdownIntentManager.consumeTicket(request.getPlayerId(), intentId);
        if (ticket.isEmpty()) {
            LOGGER.warn("Rejecting shutdown evacuation for {} - ticket missing or expired (intent {})",
                    request.getPlayerName(), intentId);
            sendDisconnectCommand(request, "shutdown-ticket-missing");
            return false;
        }
        ShutdownIntentManager.ShutdownTicket snapshot = ticket.get();
        request.setFamilyId(snapshot.fallbackFamily());
        metadata.put("shutdownServiceId", snapshot.serviceId());
        return true;
    }

    private void triggerProvisionIfNeeded(String familyId, Map<String, String> metadata) {
        if (familyId == null || familyId.isBlank()) {
            return;
        }

        if (!routingStore.acquireProvisionLock(familyId)) {
            return;
        }

        Optional<ProvisionResult> result = slotProvisionService.requestProvision(familyId, metadata);
        if (result.isPresent()) {
            ProvisionResult provision = result.get();
            LOGGER.info("Triggered slot provision for family {} on server {}", familyId, provision.serverId());
            return;
        }

        routingStore.releaseProvisionLock(familyId);
    }

    private static final Comparator<SlotCandidate> SLOT_CANDIDATE_COMPARATOR = Comparator
            .comparingDouble(SlotCandidate::fillRatio).reversed()
            .thenComparingInt(SlotCandidate::occupancy).reversed()
            .thenComparingInt(SlotCandidate::remainingCapacity)
            .thenComparingInt(SlotCandidate::order);

    private Optional<LogicalSlotRecord> findAvailableSlot(String familyId, String variantId, Set<String> blockedSlotIds) {
        if (familyId == null) {
            return Optional.empty();
        }

        List<SlotCandidate> candidates = new ArrayList<>();
        int order = 0;

        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            if (shutdownIntentManager != null && shutdownIntentManager.isServerEvacuating(server.getServerId())) {
                continue;
            }
            for (LogicalSlotRecord slot : server.getSlots()) {
                if (!SlotSelectionRules.isSlotEligible(slot)) {
                    continue;
                }
                if (isSlotBlocked(slot.getSlotId(), blockedSlotIds)) {
                    continue;
                }
                String slotFamily = slot.getMetadata().get("family");
                if (!familyId.equalsIgnoreCase(slotFamily)) {
                    continue;
                }
                if (!SlotSelectionRules.variantMatches(slot, variantId)) {
                    continue;
                }
                if (SlotSelectionRules.remainingCapacity(slot, routingStore) <= 0) {
                    continue;
                }

                SlotCandidate candidate = createSlotCandidate(slot, order++);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(SLOT_CANDIDATE_COMPARATOR);
        return Optional.of(candidates.get(0).slot());
    }

    private Optional<LogicalSlotRecord> findSlotById(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeSlotId(slotId);
        if (normalized == null) {
            return Optional.empty();
        }
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            for (LogicalSlotRecord slot : server.getSlots()) {
                if (normalized.equalsIgnoreCase(normalizeSlotId(slot.getSlotId()))) {
                    return Optional.of(slot);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSlotEligibleForRejoin(LogicalSlotRecord slot) {
        if (slot == null) {
            return false;
        }
        SlotLifecycleStatus status = slot.getStatus();
        if (status != SlotLifecycleStatus.ALLOCATED) {
            return false;
        }
        return SlotSelectionRules.remainingCapacity(slot, routingStore) > 0;
    }

    private void sendRejoinUnavailableAck(PlayerSlotRequest request) {
        if (request == null) {
            return;
        }
        PlayerRouteAck ack = new PlayerRouteAck();
        ack.setRequestId(request.getRequestId());
        ack.setPlayerId(request.getPlayerId());
        ack.setProxyId(request.getProxyId());
        ack.setStatus(PlayerRouteAck.Status.FAILED);
        ack.setReason("rejoin-slot-unavailable");
        messageBus.broadcast(ChannelConstants.PLAYER_ROUTE_ACK, ack);
    }

    private void dispatchQueuedPlayers(String familyId, LogicalSlotRecord slot) {
        if (familyId == null || slot == null) {
            return;
        }

        partyCoordinator.processPendingReservations(familyId, slot);

        if (shutdownIntentManager != null && shutdownIntentManager.isServerEvacuating(slot.getServerId())) {
            routingStore.releaseProvisionLock(familyId);
            return;
        }

        if (SlotSelectionRules.remainingCapacity(slot, routingStore) <= 0) {
            routingStore.releaseProvisionLock(familyId);
            return;
        }

        List<RedisRoutingStore.PlayerQueueEntry> deferred = new ArrayList<>();
        boolean routedAny = false;
        String slotId = slot.getSlotId();

        while (SlotSelectionRules.remainingCapacity(slot, routingStore) > 0) {
            Optional<RedisRoutingStore.PlayerQueueEntry> entryOpt = routingStore.pollPlayer(familyId);
            if (entryOpt.isEmpty()) {
                break;
            }

            PlayerRequestContext context = fromQueueEntry(entryOpt.get());
            if (context == null) {
                continue;
            }

            if (context.isBlockedSlot(slotId) || !SlotSelectionRules.variantMatches(slot, context.variantId())) {
                context.markEnqueued();
                deferred.add(toQueueEntry(context));
                continue;
            }

            if (context.hasExceededWait(MAX_QUEUE_WAIT)) {
                sendDisconnectCommand(context.request(), "queue-timeout");
                continue;
            }

            routedAny = true;
            routePlayer(context, slot);
        }

        for (RedisRoutingStore.PlayerQueueEntry entry : deferred) {
            routingStore.enqueuePlayer(familyId, entry);
        }

        if (!routedAny && !deferred.isEmpty()) {
            // Trigger provisioning with metadata from the first deferred request to encourage capacity.
            PlayerSlotRequest pendingRequest = deferred.get(0).getRequest();
            triggerProvisionIfNeeded(familyId, pendingRequest != null ? pendingRequest.getMetadata() : Map.of());
        }

        routingStore.releaseProvisionLock(familyId);
    }

    private void routePlayer(PlayerRequestContext context, LogicalSlotRecord slot) {
        PlayerSlotRequest request = context.request();

        if (context.isBlockedSlot(slot.getSlotId())) {
            LOGGER.debug("Skipping slot {} for player {} because it matches their current assignment",
                    slot.getSlotId(), request.getPlayerName());
            enqueueContext(context);
            triggerProvisionIfNeeded(request.getFamilyId(), request.getMetadata());
            return;
        }

        String currentSlotId = context.currentSlotId();
        if (currentSlotId != null && !currentSlotId.equalsIgnoreCase(slot.getSlotId())) {
            activePlayerTracker.rememberRecentSlot(request.getPlayerId(), currentSlotId);
        }

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
        PlayerSlotRequest request = context.request();

        Optional<RedisRoutingStore.MatchRosterEntry> rosterSnapshot = routingStore.getMatchRoster(slot.getSlotId());
        if (rosterSnapshot.isPresent()) {
            Set<UUID> allowedPlayers = rosterSnapshot.get().getPlayers();
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
            PartyReservationAllocation allocation = partyCoordinator.getAllocation(reservationId);
            if (allocation != null) {
                if (allocation.teamIndex() >= 0) {
                    metadata.put("team.index", Integer.toString(allocation.teamIndex()));
                }
                if (allocation.snapshot() != null && allocation.snapshot().getPartyId() != null) {
                    metadata.putIfAbsent("partyId", allocation.snapshot().getPartyId().toString());
                }
            }
        }
        command.setMetadata(metadata);

        messageBus.broadcast(ChannelConstants.getPlayerRouteChannel(request.getProxyId()), command);
        messageBus.broadcast(ChannelConstants.getServerPlayerRouteChannel(slot.getServerId()), command);
        LOGGER.info("Routing player {} to slot {} on server {}", request.getPlayerName(), slot.getSlotId(), slot.getServerId());

        if (!preReserved) {
            updatePendingOccupancy(slot.getSlotId(), 1);
        }
        routingStore.storeInFlightRoute(
                request.getRequestId(),
                new RedisRoutingStore.RouteEntry(toQueueEntry(context), slot.getSlotId(), System.currentTimeMillis())
        );
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> handleRouteTimeout(request.getRequestId()),
                ROUTE_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        );
        routeTimeouts.put(request.getRequestId(), timeout);
    }

    private void handleRouteTimeout(UUID requestId) {
        if (requestId == null) {
            return;
        }
        routeTimeouts.remove(requestId);
        Optional<RedisRoutingStore.RouteEntry> removed = routingStore.removeInFlightRoute(requestId);
        if (removed.isEmpty()) {
            return;
        }
        RedisRoutingStore.RouteEntry entry = removed.get();
        updatePendingOccupancy(entry.getSlotId(), -1);
        PlayerRequestContext context = fromQueueEntry(entry.getContext());
        if (context == null) {
            return;
        }
        LOGGER.warn("Player routing timed out for request {}", context.request().getRequestId());
        sendDisconnectCommand(context.request(), "route-timeout");
    }

    private void handleSlotUnavailable(LogicalSlotRecord slot, String reason) {
        if (slot == null) {
            return;
        }
        String slotId = slot.getSlotId();
        long pending = routingStore.getOccupancy(slotId);
        if (pending > 0) {
            updatePendingOccupancy(slotId, (int) -pending);
        }
        matchRosterService.clearForSlot(slotId);

        for (PartyReservationAllocation allocation : partyCoordinator.getActiveAllocations()) {
            if (slotId.equalsIgnoreCase(allocation.slotId())) {
                LOGGER.warn("Re-queueing party reservation {} after slot {} became unavailable", allocation.reservationId(), slotId);
                partyCoordinator.requeueAllocation(allocation);
            }
        }

        int affected = 0;
        for (RedisRoutingStore.StoredRoute storedRoute : routingStore.getInFlightRoutes()) {
            RedisRoutingStore.RouteEntry entry = storedRoute.entry();
            if (!slotId.equalsIgnoreCase(entry.getSlotId())) {
                continue;
            }
            routingStore.removeInFlightRoute(storedRoute.requestId());
            Optional.ofNullable(routeTimeouts.remove(storedRoute.requestId()))
                    .ifPresent(future -> future.cancel(false));
            updatePendingOccupancy(entry.getSlotId(), -1);
            PlayerRequestContext context = fromQueueEntry(entry.getContext());
            if (context != null) {
                retryRequest(context, reason);
                affected++;
            }
        }

        if (affected > 0) {
            LOGGER.warn("Slot {} became unavailable; re-queued {} pending players", slotId, affected);
        }
    }

    private void retryRequest(PlayerRequestContext context, String reason) {
        if (context == null) {
            return;
        }

        if (context.hasExceededWait(MAX_QUEUE_WAIT)) {
            sendDisconnectCommand(context.request(), reason != null ? reason : "queue-timeout");
            return;
        }

        if (!context.registerRetry(MAX_ROUTE_RETRIES)) {
            sendDisconnectCommand(context.request(), reason != null ? reason : "route-failed");
            return;
        }

        enqueueContext(context);
        triggerProvisionIfNeeded(context.request().getFamilyId(), context.request().getMetadata());
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

    private void updatePendingOccupancy(String slotId, int delta) {
        if (slotId == null || delta == 0) {
            return;
        }
        if (delta > 0) {
            for (int index = 0; index < delta; index++) {
                routingStore.incrementOccupancy(slotId);
            }
        } else {
            for (int index = 0; index < Math.abs(delta); index++) {
                routingStore.decrementOccupancy(slotId);
            }
        }
    }

    private void handlePartyReservationClaimed(MessageEnvelope envelope) {
        try {
            PartyReservationClaimedMessage message = convert(envelope.payload(), PartyReservationClaimedMessage.class);
            if (message == null) {
                return;
            }
            partyCoordinator.handleReservationClaimed(message);
        } catch (Exception exception) {
            LOGGER.error("Failed to handle party reservation claim message", exception);
        }
    }

    private boolean isSlotBlocked(String slotId, Set<String> blockedSlotIds) {
        if (blockedSlotIds == null || blockedSlotIds.isEmpty()) {
            return false;
        }
        String normalized = normalizeSlotId(slotId);
        return normalized != null && blockedSlotIds.contains(normalized);
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

    private SlotCandidate createSlotCandidate(LogicalSlotRecord slot, int order) {
        if (slot == null) {
            return null;
        }
        String slotId = slot.getSlotId();
        long pending = routingStore.getOccupancy(slotId);
        int occupancy = slot.getOnlinePlayers() + (int) pending;
        int remaining = SlotSelectionRules.remainingCapacity(slot, routingStore);
        int max = slot.getMaxPlayers();
        double ratio;
        if (max > 0) {
            ratio = (double) occupancy / max;
        } else if (occupancy > 0) {
            ratio = 1.0D;
        } else {
            ratio = 0.0D;
        }
        return new SlotCandidate(slot, occupancy, remaining, ratio, order);
    }

    private record SlotCandidate(LogicalSlotRecord slot,
                                 int occupancy,
                                 int remainingCapacity,
                                 double fillRatio,
                                 int order) {
    }
}
