package sh.harold.fulcrum.velocity.friends;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.social.*;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.fundamentals.identity.PlayerIdentity;
import sh.harold.fulcrum.velocity.fundamentals.identity.VelocityIdentityFeature;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.time.Duration;
import java.util.*;
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
    private final ProxyServer proxy;
    private final LettuceSessionRedisClient redisClient;
    private final Logger logger;
    private final RankService rankService;
    private final VelocityIdentityFeature identityFeature;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<UUID, FriendSnapshot> localSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<FriendOperationResult>> pendingMutations = new ConcurrentHashMap<>();
    private final MessageHandler mutationResponseHandler = this::handleMutationResponse;
    private final MessageHandler requestEventHandler = envelope -> handleRelationEvent(envelope, true);
    private final MessageHandler updateEventHandler = envelope -> handleRelationEvent(envelope, false);
    private final MessageHandler blockEventHandler = this::handleBlockEvent;

    public VelocityFriendService(MessageBus messageBus,
                                 LettuceSessionRedisClient redisClient,
                                 ProxyServer proxy,
                                 RankService rankService,
                                 VelocityIdentityFeature identityFeature,
                                 Logger logger) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.rankService = Objects.requireNonNull(rankService, "rankService");
        this.identityFeature = identityFeature;

        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_MUTATION_RESPONSE, mutationResponseHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_REQUESTS, requestEventHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_UPDATES, updateEventHandler);
        messageBus.subscribe(ChannelConstants.SOCIAL_FRIEND_BLOCKS, blockEventHandler);
        debug("VelocityFriendService initialised; redisAvailable={} busSubscriptionsReady=true", redisClient.isAvailable());
    }

    @Override
    public CompletionStage<FriendSnapshot> getSnapshot(UUID playerId, boolean forceReload) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(FriendSnapshot.empty());
        }
        debug("Snapshot requested player={} forceReload={}", playerId, forceReload);
        if (!forceReload) {
            FriendSnapshot cached = localSnapshots.get(playerId);
            if (cached != null) {
                debug("Local snapshot cache hit player={} version={}", playerId, cached.version());
                return CompletableFuture.completedFuture(cached);
            }
        }
        FriendSnapshot snapshot = refreshFromRedis(playerId).orElse(FriendSnapshot.empty());
        debug("Snapshot request resolved player={} version={}", playerId, snapshot.version());
        return CompletableFuture.completedFuture(snapshot);
    }

    @Override
    public CompletionStage<FriendOperationResult> execute(FriendMutationRequest request) {
        Objects.requireNonNull(request, "request");
        UUID requestId = UUID.randomUUID();
        debug("Dispatching mutation requestId={} type={} actor={} target={} scope={} expiresAt={} metadataKeys={}",
                requestId, request.type(), request.actorId(), request.targetId(), request.scope(), request.expiresAt(), request.metadata().keySet());

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
        debug("Pending mutation registered requestId={} currentPending={}", requestId, pendingMutations.size());
        try {
            messageBus.send(REGISTRY_SERVER_ID, ChannelConstants.SOCIAL_FRIEND_MUTATION_REQUEST, command);
            debug("Mutation dispatched to registry requestId={} channel={}", requestId, ChannelConstants.SOCIAL_FRIEND_MUTATION_REQUEST);
        } catch (Exception ex) {
            pendingMutations.remove(requestId);
            debug("Mutation dispatch failed requestId={} error={}", requestId, ex.toString());
            future.completeExceptionally(ex);
            return future;
        }

        return future.orTimeout(MUTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, throwable) -> {
                    pendingMutations.remove(requestId);
                    if (throwable != null) {
                        debug("Mutation completed exceptionally requestId={} error={}", requestId, throwable.toString());
                    } else if (result != null) {
                        debug("Mutation completed requestId={} success={} error={}", requestId, result.success(), result.errorMessage().orElse(null));
                    } else {
                        debug("Mutation completed without result payload requestId={}", requestId);
                    }
                });
    }

    @Override
    public CompletionStage<List<FriendService.PendingFriendInvite>> getPendingInvites(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> fetchPendingInvites(playerId));
    }

    public void shutdown() {
        debug("Shutting down friend service; pendingMutations={}", pendingMutations.size());
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
            debug("Handling mutation response envelope sender={}", envelope.senderId());
            FriendMutationResponseMessage response = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), FriendMutationResponseMessage.class);
            if (response == null) {
                debug("Mutation response payload was empty");
                return;
            }
            debug("Decoded mutation response requestId={} success={} actor={} target={} error={}",
                    response.getRequestId(), response.isSuccess(), response.getActorId(), response.getTargetId(), response.getError());
            if (response.getRequestId() == null) {
                debug("Mutation response missing request id");
                return;
            }
            CompletableFuture<FriendOperationResult> pending = pendingMutations.get(response.getRequestId());
            if (pending == null) {
                debug("No pending mutation future for requestId={}", response.getRequestId());
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
            debug("Completed pending mutation requestId={} success={} error={}", response.getRequestId(), result.success(), result.errorMessage().orElse(null));
            if (response.getActorId() != null && response.getActorSnapshot() != null) {
                localSnapshots.put(response.getActorId(), response.getActorSnapshot());
                debug("Cached actor snapshot from response actor={} version={}", response.getActorId(), response.getActorSnapshot().version());
            }
            if (response.getTargetId() != null && response.getTargetSnapshot() != null) {
                localSnapshots.put(response.getTargetId(), response.getTargetSnapshot());
                debug("Cached target snapshot from response target={} version={}", response.getTargetId(), response.getTargetSnapshot().version());
            }
        } catch (Exception ex) {
            logger.warn("Failed to process friend mutation response", ex);
        }
    }

    private void handleRelationEvent(MessageEnvelope envelope, boolean requestChannel) {
        try {
            debug("Handling {} relation event envelope sender={}", requestChannel ? "request" : "update", envelope.senderId());
            Class<? extends FriendRelationEventMessage> messageType = requestChannel
                    ? FriendRequestEventMessage.class
                    : FriendRelationEventMessage.class;
            FriendRelationEventMessage event = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), messageType);
            if (event == null) {
                debug("Relation event payload was empty for channel {}", requestChannel ? ChannelConstants.SOCIAL_FRIEND_REQUESTS : ChannelConstants.SOCIAL_FRIEND_UPDATES);
                return;
            }
            debug("Decoded relation event requestChannel={} owner={} peer={} ownerVersion={} peerVersion={}",
                    requestChannel, event.getOwnerId(), event.getPeerId(), event.getOwnerVersion(), event.getPeerVersion());
            if (event.getOwnerId() != null) {
                refreshIfStale(event.getOwnerId(), event.getOwnerVersion());
            }
            if (event.getPeerId() != null) {
                refreshIfStale(event.getPeerId(), event.getPeerVersion());
            }
            notifyRelationEvent(event);
        } catch (Exception ex) {
            logger.debug("Failed to process friend relation event", ex);
        }
    }

    private void handleBlockEvent(MessageEnvelope envelope) {
        try {
            debug("Handling block event envelope sender={}", envelope.senderId());
            FriendBlockEventMessage event = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), FriendBlockEventMessage.class);
            if (event == null) {
                debug("Block event payload empty");
                return;
            }
            debug("Decoded block event owner={} target={} active={} scope={} ownerVersion={} targetVersion={}",
                    event.getOwnerId(), event.getTargetId(), event.isActive(), event.getScope(), event.getOwnerVersion(), event.getTargetVersion());
            if (event.getOwnerId() != null) {
                refreshIfStale(event.getOwnerId(), event.getOwnerVersion());
            }
            if (event.getTargetId() != null) {
                refreshIfStale(event.getTargetId(), event.getTargetVersion());
            }
            notifyBlockEvent(event);
        } catch (Exception ex) {
            logger.debug("Failed to process friend block event", ex);
        }
    }

    private void refreshIfStale(UUID playerId, long eventVersion) {
        FriendSnapshot existing = localSnapshots.get(playerId);
        long currentVersion = existing != null ? existing.version() : -1L;
        if (existing == null || eventVersion > existing.version()) {
            debug("Refreshing snapshot from Redis player={} eventVersion={} currentVersion={}", playerId, eventVersion, currentVersion);
            refreshFromRedis(playerId).ifPresent(snapshot -> localSnapshots.put(playerId, snapshot));
        } else {
            debug("Skipping snapshot refresh player={} eventVersion={} currentVersion={}", playerId, eventVersion, currentVersion);
        }
    }

    private Optional<FriendSnapshot> refreshFromRedis(UUID playerId) {
        try {
            String key = FriendRedisKeys.snapshotKey(playerId);
            debug("Fetching Redis snapshot key={} player={}", key, playerId);
            String payload = redisClient.get(key);
            if (payload == null || payload.isBlank()) {
                localSnapshots.remove(playerId);
                debug("Redis snapshot miss key={} player={}", key, playerId);
                return Optional.empty();
            }
            FriendSnapshot snapshot = mapper.readValue(payload, FriendSnapshot.class);
            localSnapshots.put(playerId, snapshot);
            debug("Redis snapshot hydrated key={} player={} version={}", key, playerId, snapshot.version());
            return Optional.of(snapshot);
        } catch (Exception ex) {
            logger.debug("Failed to hydrate friend snapshot from Redis for {}", playerId, ex);
            debug("Redis snapshot hydrate failed player={} error={}", playerId, ex.toString());
            return Optional.empty();
        }
    }

    private List<FriendService.PendingFriendInvite> fetchPendingInvites(UUID playerId) {
        Set<String> actorIds = redisClient.smembers(FriendRedisKeys.pendingInvitesKey(playerId));
        if (actorIds == null || actorIds.isEmpty()) {
            return List.of();
        }
        List<FriendService.PendingFriendInvite> invites = new ArrayList<>();
        for (String actorId : actorIds) {
            try {
                UUID actorUuid = UUID.fromString(actorId);
                String key = FriendRedisKeys.inviteKey(actorUuid, playerId);
                String payload = redisClient.get(key);
                if (payload == null || payload.isBlank()) {
                    continue;
                }
                FriendService.PendingFriendInvite invite = mapper.readValue(payload, FriendService.PendingFriendInvite.class);
                invites.add(invite);
            } catch (Exception ex) {
                debug("Failed to read pending invite actor={} error={}", actorId, ex.toString());
            }
        }
        invites.sort(Comparator.comparingLong(FriendService.PendingFriendInvite::requestedAtEpochMillis).reversed());
        return invites;
    }

    private void notifyRelationEvent(FriendRelationEventMessage event) {
        if (event == null || event.getMutationType() == null) {
            return;
        }
        UUID owner = event.getOwnerId();
        UUID peer = event.getPeerId();
        switch (event.getMutationType()) {
            case INVITE_SEND -> notifyFriendRequest(owner, peer);
            case INVITE_ACCEPT -> notifyFriendAcceptance(owner, peer);
            case INVITE_DECLINE -> notifyFriendDecline(owner, peer);
            case INVITE_CANCEL -> notifyFriendCancel(owner, peer);
            case UNFRIEND -> notifyFriendRemoval(owner, peer);
            default -> {
            }
        }
    }

    private void notifyFriendRequest(UUID senderId, UUID recipientId) {
        if (senderId == null || recipientId == null) {
            return;
        }
        Component senderName = formatDisplayName(senderId);
        String senderPlain = resolvePlainName(senderId);
        Component body = Component.text()
                .append(senderName)
                .append(FriendTextFormatter.gray(" sent you a friend request. "))
                .append(FriendTextFormatter.gray("Use "))
                .append(FriendTextFormatter.aqua("/friend accept " + senderPlain))
                .append(FriendTextFormatter.gray(" or "))
                .append(FriendTextFormatter.aqua("/friend deny " + senderPlain))
                .append(FriendTextFormatter.gray("."))
                .build();
        sendMessage(recipientId, prefixed(body));
    }

    private void notifyFriendAcceptance(UUID acceptorId, UUID partnerId) {
        if (acceptorId == null || partnerId == null) {
            return;
        }
        Component partnerName = formatDisplayName(partnerId);
        Component acceptorName = formatDisplayName(acceptorId);

        Component acceptorBody = Component.text()
                .append(FriendTextFormatter.gray("You are now friends with "))
                .append(partnerName)
                .append(FriendTextFormatter.gray("."))
                .build();
        sendMessage(acceptorId, prefixed(acceptorBody));

        Component partnerBody = Component.text()
                .append(acceptorName)
                .append(FriendTextFormatter.gray(" accepted your friend request. You're now friends."))
                .build();
        sendMessage(partnerId, prefixed(partnerBody));
    }

    private void notifyFriendDecline(UUID declinerId, UUID requesterId) {
        if (declinerId == null || requesterId == null) {
            return;
        }
        Component requesterName = formatDisplayName(requesterId);
        Component declinerName = formatDisplayName(declinerId);

        Component declinerBody = Component.text()
                .append(FriendTextFormatter.gray("You declined the friend request from "))
                .append(requesterName)
                .append(FriendTextFormatter.gray("."))
                .build();
        sendMessage(declinerId, prefixed(declinerBody));

        Component requesterBody = Component.text()
                .append(declinerName)
                .append(FriendTextFormatter.gray(" declined your friend request."))
                .build();
        sendMessage(requesterId, prefixed(requesterBody));
    }

    private void notifyFriendCancel(UUID senderId, UUID recipientId) {
        if (senderId == null || recipientId == null) {
            return;
        }
        Component recipientName = formatDisplayName(recipientId);
        Component senderName = formatDisplayName(senderId);

        Component senderBody = Component.text()
                .append(FriendTextFormatter.gray("You cancelled your friend request to "))
                .append(recipientName)
                .append(FriendTextFormatter.gray("."))
                .build();
        sendMessage(senderId, prefixed(senderBody));

        Component recipientBody = Component.text()
                .append(senderName)
                .append(FriendTextFormatter.gray(" cancelled their friend request."))
                .build();
        sendMessage(recipientId, prefixed(recipientBody));
    }

    private void notifyFriendRemoval(UUID removerId, UUID removedId) {
        if (removerId == null || removedId == null) {
            return;
        }
        Component removedName = formatDisplayName(removedId);
        Component removerName = formatDisplayName(removerId);

        Component removerBody = Component.text()
                .append(FriendTextFormatter.gray("You removed "))
                .append(removedName)
                .append(FriendTextFormatter.gray(" from your friends."))
                .build();
        sendMessage(removerId, prefixed(removerBody));

        Component removedBody = Component.text()
                .append(removerName)
                .append(FriendTextFormatter.gray(" removed you from their friends list."))
                .build();
        sendMessage(removedId, prefixed(removedBody));
    }

    private void notifyBlockEvent(FriendBlockEventMessage event) {
        if (event == null || event.getOwnerId() == null) {
            return;
        }
        UUID ownerId = event.getOwnerId();
        UUID targetId = event.getTargetId();
        Component targetName = formatDisplayName(targetId);
        String scopeLabel = event.getScope() != null
                ? event.getScope().name().toLowerCase(Locale.ROOT)
                : "global";
        Component body;
        if (event.isActive()) {
            body = Component.text()
                    .append(FriendTextFormatter.gray("You blocked "))
                    .append(targetName)
                    .append(FriendTextFormatter.gray(" for "))
                    .append(FriendTextFormatter.gray(scopeLabel + " interactions."))
                    .build();
        } else {
            body = Component.text()
                    .append(FriendTextFormatter.gray("You unblocked "))
                    .append(targetName)
                    .append(FriendTextFormatter.gray(" for "))
                    .append(FriendTextFormatter.gray(scopeLabel + " interactions."))
                    .build();
        }
        sendMessage(ownerId, prefixed(body));
    }

    private void sendMessage(UUID playerId, Component message) {
        if (playerId == null || message == null) {
            return;
        }
        proxy.getPlayer(playerId).ifPresent(player -> player.sendMessage(message));
    }

    private Component prefixed(Component body) {
        return Component.text()
                .append(FriendTextFormatter.aqua("[Friends] "))
                .append(body)
                .build();
    }

    private Component formatDisplayName(UUID playerId) {
        return FriendTextFormatter.formatName(playerId, resolvePlainName(playerId), rankService, logger);
    }

    private String resolvePlainName(UUID playerId) {
        if (playerId == null) {
            return "unknown";
        }
        Optional<String> onlineName = proxy.getPlayer(playerId).map(Player::getUsername);
        if (onlineName.isPresent()) {
            return onlineName.get();
        }
        if (identityFeature != null) {
            PlayerIdentity identity = identityFeature.getIdentity(playerId);
            if (identity != null && identity.getUsername() != null && !identity.getUsername().isBlank()) {
                return identity.getUsername();
            }
        }
        return shortUuid(playerId);
    }

    private String shortUuid(UUID playerId) {
        if (playerId == null) {
            return "unknown";
        }
        String[] parts = playerId.toString().split("-");
        return parts.length > 0 ? parts[0] : playerId.toString();
    }

    private void debug(String message, Object... args) {
        logger.info("[velocity-friends] " + message, args);
    }
}
