package sh.harold.fulcrum.velocity.friends;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.social.*;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Velocity-side {@link FriendService} backed by Redis snapshots and registry mutations.
 */
public final class VelocityFriendService implements FriendService {
    private static final String REGISTRY_SERVER_ID = "registry-service";
    private static final Duration MUTATION_TIMEOUT = Duration.ofSeconds(10);

    private final MessageBus messageBus;
    private final LettuceSessionRedisClient redisClient;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, FriendSnapshot> localSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<FriendOperationResult>> pendingMutations = new ConcurrentHashMap<>();
    private final MessageHandler mutationResponseHandler = this::handleMutationResponse;
    private final MessageHandler requestEventHandler = envelope -> handleRelationEvent(envelope, true);
    private final MessageHandler updateEventHandler = envelope -> handleRelationEvent(envelope, false);
    private final MessageHandler blockEventHandler = this::handleBlockEvent;

    public VelocityFriendService(MessageBus messageBus,
                                 LettuceSessionRedisClient redisClient,
                                 Logger logger) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.logger = Objects.requireNonNull(logger, "logger");

        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_MUTATION_RESPONSE, mutationResponseHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_REQUESTS, requestEventHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_UPDATES, updateEventHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_BLOCKS, blockEventHandler);
    }

    @Override
    public CompletionStage<FriendSnapshot> getSnapshot(UUID playerId, boolean forceReload) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(FriendSnapshot.empty());
        }
        if (!forceReload) {
            FriendSnapshot cached = localSnapshots.get(playerId);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        FriendSnapshot snapshot = refreshFromRedis(playerId).orElse(FriendSnapshot.empty());
        return CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public CompletionStage<FriendOperationResult> execute(FriendMutationRequest request) {
        Objects.requireNonNull(request, "request");
        UUID requestId = UUID.randomUUID();

        FriendMutationCommandMessage command = new FriendMutationCommandMessage();
        command.setRequestId(requestId);
        command.setMutationType(request.type());
        command.setActorId(request.actorId());
        command.setTargetId(request.targetId());
        command.setScope(request.scope());
        command.setReason(request.reason());
        command.setMetadata(request.metadata());
        if (request.expiresAt() != null) {
            command.setExpiresAtEpochMillis(request.expiresAt().toEpochMilli());
        }

        CompletableFuture<FriendOperationResult> future = new CompletableFuture<>();
        pendingMutations.put(requestId, future);
        try {
            messageBus.send(REGISTRY_SERVER_ID, ChannelConstants.SOCIAL_FRIEND_MUTATION_REQUEST, command);
        } catch (Exception ex) {
            pendingMutations.remove(requestId);
            future.completeExceptionally(ex);
            return future;
        }

        return future.orTimeout(MUTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ignored, throwable) -> pendingMutations.remove(requestId));
    }

    public void shutdown() {
        messageBus.unsubscribe(ChannelConstants.SOCIAL_FRIEND_MUTATION_RESPONSE, mutationResponseHandler);
        messageBus.unsubscribe(ChannelConstants.SOCIAL_FRIEND_REQUESTS, requestEventHandler);
        messageBus.unsubscribe(ChannelConstants.SOCIAL_FRIEND_UPDATES, updateEventHandler);
        messageBus.unsubscribe(ChannelConstants.SOCIAL_FRIEND_BLOCKS, blockEventHandler);
        redisClient.close();
        localSnapshots.clear();
        pendingMutations.forEach((id, future) -> future.completeExceptionally(new IllegalStateException("Friend service shutting down")));
        pendingMutations.clear();
    }

    private void handleMutationResponse(MessageEnvelope envelope) {
        try {
            FriendMutationResponseMessage response = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), FriendMutationResponseMessage.class);
            if (response == null || response.getRequestId() == null) {
                return;
            }
            CompletableFuture<FriendOperationResult> pending = pendingMutations.get(response.getRequestId());
            if (pending == null) {
                return;
            }
            FriendOperationResult result = new FriendOperationResult(
                    response.isSuccess(),
                    response.getMutationType(),
                    response.getActorSnapshot(),
                    response.getTargetSnapshot(),
                    response.getError()
            );
            if (!pending.isDone()) {
                pending.complete(result);
            }
            if (response.getActorId() != null && response.getActorSnapshot() != null) {
                localSnapshots.put(response.getActorId(), response.getActorSnapshot());
            }
            if (response.getTargetId() != null && response.getTargetSnapshot() != null) {
                localSnapshots.put(response.getTargetId(), response.getTargetSnapshot());
            }
        } catch (Exception ex) {
            logger.warn("Failed to process friend mutation response", ex);
        }
    }

    private void handleRelationEvent(MessageEnvelope envelope, boolean requestChannel) {
        try {
            Class<? extends FriendRelationEventMessage> messageType = requestChannel
                    ? FriendRequestEventMessage.class
                    : FriendRelationEventMessage.class;
            FriendRelationEventMessage event = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), messageType);
            if (event == null) {
                return;
            }
            if (event.getOwnerId() != null) {
                refreshIfStale(event.getOwnerId(), event.getOwnerVersion());
            }
            if (event.getPeerId() != null) {
                refreshIfStale(event.getPeerId(), event.getPeerVersion());
            }
        } catch (Exception ex) {
            logger.debug("Failed to process friend relation event", ex);
        }
    }

    private void handleBlockEvent(MessageEnvelope envelope) {
        try {
            FriendBlockEventMessage event = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), FriendBlockEventMessage.class);
            if (event == null) {
                return;
            }
            if (event.getOwnerId() != null) {
                refreshIfStale(event.getOwnerId(), event.getOwnerVersion());
            }
            if (event.getTargetId() != null) {
                refreshIfStale(event.getTargetId(), event.getTargetVersion());
            }
        } catch (Exception ex) {
            logger.debug("Failed to process friend block event", ex);
        }
    }

    private void refreshIfStale(UUID playerId, long eventVersion) {
        FriendSnapshot existing = localSnapshots.get(playerId);
        if (existing == null || eventVersion > existing.version()) {
            refreshFromRedis(playerId).ifPresent(snapshot -> localSnapshots.put(playerId, snapshot));
        }
    }

    private Optional<FriendSnapshot> refreshFromRedis(UUID playerId) {
        try {
            String payload = redisClient.get(FriendRedisKeys.snapshotKey(playerId));
            if (payload == null || payload.isBlank()) {
                localSnapshots.remove(playerId);
                return Optional.empty();
            }
            FriendSnapshot snapshot = mapper.readValue(payload, FriendSnapshot.class);
            localSnapshots.put(playerId, snapshot);
            return Optional.of(snapshot);
        } catch (Exception ex) {
            logger.debug("Failed to hydrate friend snapshot from Redis for {}", playerId, ex);
            return Optional.empty();
        }
    }
}
