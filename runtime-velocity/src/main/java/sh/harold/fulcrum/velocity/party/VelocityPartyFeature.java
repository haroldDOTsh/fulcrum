package sh.harold.fulcrum.velocity.party;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterCreatedMessage;
import sh.harold.fulcrum.api.messagebus.messages.match.MatchRosterEndedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationClaimedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyReservationCreatedMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage;
import sh.harold.fulcrum.api.party.*;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.config.ConfigLoader;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.fundamentals.family.SlotFamilyCache;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPartyFeature implements VelocityFeature {
    private static final Component FRAME_LINE = Component.text("-----------------------------------------------------", NamedTextColor.BLUE)
            .decorate(TextDecoration.STRIKETHROUGH);

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Set<UUID> localPlayers = ConcurrentHashMap.newKeySet();

    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private ConfigLoader configLoader;
    private MessageBus messageBus;
    private Logger logger;
    private ServiceLocator serviceLocator;
    private SlotFamilyCache familyCache;
    private DataAPI dataAPI;
    private VelocityPlayerSessionService sessionService;

    private VelocityRedisOperations redis;
    private PartyServiceImpl partyService;
    private PartyPresenceListener listener;
    private ScheduledTask inviteCleanupTask;
    private MessageHandler updateHandler;
    private MessageHandler reservationHandler;
    private MessageHandler reservationClaimedHandler;
    private MessageHandler matchRosterHandler;
    private MessageHandler matchRosterEndedHandler;
    private PartyReservationStore reservationStore;
    private PartyReservationService reservationService;
    private PartyReservationLifecycleTracker reservationTracker;
    private PartyMatchRosterStore rosterStore;
    private PlayerRoutingFeature routingFeature;

    @Override
    public String getName() {
        return "VelocityParty";
    }

    @Override
    public int getPriority() {
        return 95; // after routing but before generic commands
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        this.serviceLocator = serviceLocator;
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.configLoader = serviceLocator.getRequiredService(ConfigLoader.class);
        this.messageBus = serviceLocator.getRequiredService(MessageBus.class);
        this.familyCache = serviceLocator.getRequiredService(SlotFamilyCache.class);
        this.routingFeature = serviceLocator.getRequiredService(PlayerRoutingFeature.class);
        this.dataAPI = serviceLocator.getService(DataAPI.class).orElse(null);
        this.sessionService = serviceLocator.getService(VelocityPlayerSessionService.class).orElse(null);

        RedisConfig redisConfig = configLoader.getConfig(RedisConfig.class);
        if (redisConfig == null) {
            logger.warn("PartyFeature disabled - Redis configuration missing.");
            return;
        }

        this.redis = new VelocityRedisOperations(redisConfig, logger);
        PartyRepository repository = new RedisPartyRepository(redis, logger);
        PartyLockManager lockManager = new PartyLockManager(redis, Duration.ofSeconds(5));
        this.partyService = new PartyServiceImpl(repository, lockManager, messageBus, logger);

        serviceLocator.register(PartyService.class, partyService);

        rosterStore = new PartyMatchRosterStore();
        serviceLocator.register(PartyMatchRosterStore.class, rosterStore);
        reservationTracker = new PartyReservationLifecycleTracker();
        reservationStore = new PartyReservationStore(redis, logger);
        reservationService = new PartyReservationService(partyService, reservationStore, reservationTracker, familyCache, messageBus, logger);
        serviceLocator.register(PartyReservationService.class, reservationService);

        registerCommands();
        registerListeners();
        subscribeToUpdates();

        proxy.getAllPlayers().forEach(player -> localPlayers.add(player.getUniqueId()));

        inviteCleanupTask = proxy.getScheduler()
                .buildTask(plugin, () -> {
                    try {
                        partyService.purgeExpiredInvites();
                        partyService.performMaintenance();
                        if (rosterStore != null) {
                            rosterStore.purgeExpired();
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed to purge expired invites", ex);
                    }
                })
                .repeat(Duration.ofSeconds(10))
                .schedule();

        logger.info("VelocityPartyFeature initialised");
    }

    @Override
    public void shutdown() {
        if (inviteCleanupTask != null) {
            inviteCleanupTask.cancel();
        }
        if (listener != null) {
            proxy.getEventManager().unregisterListener(plugin, listener);
        }
        if (updateHandler != null) {
            messageBus.unsubscribe(ChannelConstants.PARTY_UPDATE, updateHandler);
        }
        if (reservationHandler != null) {
            messageBus.unsubscribe(ChannelConstants.PARTY_RESERVATION_CREATED, reservationHandler);
        }
        if (reservationClaimedHandler != null) {
            messageBus.unsubscribe(ChannelConstants.PARTY_RESERVATION_CLAIMED, reservationClaimedHandler);
        }
        if (matchRosterHandler != null) {
            messageBus.unsubscribe(ChannelConstants.MATCH_ROSTER_CREATED, matchRosterHandler);
        }
        if (matchRosterEndedHandler != null) {
            messageBus.unsubscribe(ChannelConstants.MATCH_ROSTER_ENDED, matchRosterEndedHandler);
        }
        if (redis != null) {
            redis.close();
        }
        localPlayers.clear();
        if (serviceLocator != null) {
            serviceLocator.unregister(PartyReservationService.class);
            serviceLocator.unregister(PartyService.class);
            serviceLocator.unregister(PartyMatchRosterStore.class);
        }
        reservationService = null;
        reservationStore = null;
        rosterStore = null;
        logger.info("VelocityPartyFeature shut down");
    }

    private void registerCommands() {
        CommandMeta meta = proxy.getCommandManager().metaBuilder("party")
                .plugin(plugin)
                .build();
        PartyCommand command = new PartyCommand(
                partyService,
                reservationService,
                proxy,
                routingFeature,
                rosterStore,
                dataAPI,
                sessionService,
                logger
        );
        proxy.getCommandManager().register(meta, command);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("p").plugin(plugin).build(), command);
    }

    private void registerListeners() {
        listener = new PartyPresenceListener(partyService, logger, localPlayers);
        proxy.getEventManager().register(plugin, listener);
    }

    private void subscribeToUpdates() {
        updateHandler = this::handlePartyUpdateEnvelope;
        messageBus.subscribe(ChannelConstants.PARTY_UPDATE, updateHandler);
        reservationHandler = this::handleReservationCreatedEnvelope;
        messageBus.subscribe(ChannelConstants.PARTY_RESERVATION_CREATED, reservationHandler);
        reservationClaimedHandler = this::handleReservationClaimedEnvelope;
        messageBus.subscribe(ChannelConstants.PARTY_RESERVATION_CLAIMED, reservationClaimedHandler);
        matchRosterHandler = this::handleMatchRosterEnvelope;
        messageBus.subscribe(ChannelConstants.MATCH_ROSTER_CREATED, matchRosterHandler);
        matchRosterEndedHandler = this::handleMatchRosterEndedEnvelope;
        messageBus.subscribe(ChannelConstants.MATCH_ROSTER_ENDED, matchRosterEndedHandler);
    }

    private void handlePartyUpdateEnvelope(MessageEnvelope envelope) {
        try {
            PartyUpdateMessage message = convert(envelope.getPayload(), PartyUpdateMessage.class);
            if (message == null || message.getPartyId() == null) {
                return;
            }
            PartySnapshot snapshot = message.getSnapshot();
            if (snapshot == null) {
                return;
            }
            UUID actorId = message.getActorPlayerId();
            UUID targetId = message.getTargetPlayerId();
            String reason = message.getReason();

            switch (message.getAction()) {
                case INVITE_SENT -> {
                    Component broadcast = buildInviteBroadcast(snapshot, message);
                    broadcastToParty(snapshot, broadcast);

                    Component inviteeMessage = Component.text()
                            .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                            .append(PartyTextFormatter.yellow(" has invited you to join their party! You have "))
                            .append(PartyTextFormatter.redNumber(PartyConstants.INVITE_TTL_SECONDS))
                            .append(PartyTextFormatter.yellow(" seconds to accept. "))
                            .build();
                    notifyPlayer(targetId, inviteeMessage);
                }
                case INVITE_ACCEPTED, MEMBER_JOINED -> {
                    Component joined = Component.text()
                            .append(formatRankedName(targetId, safeName(snapshot, targetId)))
                            .append(PartyTextFormatter.yellow(" has joined the party!"))
                            .build();
                    broadcastToParty(snapshot, joined);
                }
                case INVITE_REVOKED, INVITE_EXPIRED -> {
                    Component expired = Component.text()
                            .append(PartyTextFormatter.yellow("The party invite to "))
                            .append(formatRankedName(targetId, safeName(snapshot, targetId)))
                            .append(PartyTextFormatter.yellow(" has expired."))
                            .build();
                    broadcastToParty(snapshot, expired);
                    notifyPlayer(targetId, PartyTextFormatter.yellow("Your party invite has expired."));
                }
                case MEMBER_LEFT -> {
                    Component left = Component.text()
                            .append(formatRankedName(actorId, resolveLeavingMemberName(snapshot, message)))
                            .append(PartyTextFormatter.yellow(" left the party."))
                            .build();
                    broadcastToParty(snapshot, left);
                }
                case MEMBER_KICKED -> {
                    Component kickedMessage;
                    if (reason != null && reason.startsWith("offline")) {
                        kickedMessage = Component.text()
                                .append(PartyTextFormatter.yellow("Kicked "))
                                .append(formatRankedName(targetId, resolveRemovedMemberName(snapshot, message)))
                                .append(PartyTextFormatter.yellow(" because they were offline."))
                                .build();
                    } else {
                        kickedMessage = Component.text()
                                .append(formatRankedName(targetId, resolveRemovedMemberName(snapshot, message)))
                                .append(PartyTextFormatter.yellow(" was removed from the party."))
                                .build();
                    }
                    broadcastToParty(snapshot, kickedMessage);
                    notifyPlayer(targetId, PartyTextFormatter.yellow("You were removed from the party."));
                }
                case TRANSFERRED -> {
                    Component promoted = Component.text()
                            .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                            .append(PartyTextFormatter.yellow(" has promoted "))
                            .append(formatRankedName(targetId, safeName(snapshot, targetId)))
                            .append(PartyTextFormatter.yellow(" to Party Leader."))
                            .build();
                    broadcastToParty(snapshot, promoted);

                    Component demoted = Component.text()
                            .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                            .append(PartyTextFormatter.yellow(" is now a Party Moderator."))
                            .build();
                    broadcastToParty(snapshot, demoted);

                    Component transferred = Component.text()
                            .append(PartyTextFormatter.yellow("The party was transferred to "))
                            .append(formatRankedName(targetId, safeName(snapshot, targetId)))
                            .append(PartyTextFormatter.yellow(" by "))
                            .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                            .build();
                    broadcastToParty(snapshot, transferred);
                }
                case ROLE_CHANGED -> {
                    PartyMember member = snapshot.getMember(targetId);
                    if (member != null) {
                        if (member.getRole() == PartyRole.MODERATOR) {
                            Component promoted = Component.text()
                                    .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                                    .append(PartyTextFormatter.yellow(" has promoted "))
                                    .append(formatRankedName(targetId, member.getUsername()))
                                    .append(PartyTextFormatter.yellow(" to Party Moderator."))
                                    .build();
                            broadcastToParty(snapshot, promoted);
                        } else if (member.getRole() == PartyRole.MEMBER) {
                            Component demoted = Component.text()
                                    .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                                    .append(PartyTextFormatter.yellow(" has demoted "))
                                    .append(formatRankedName(targetId, member.getUsername()))
                                    .append(PartyTextFormatter.yellow(" to Party Member."))
                                    .build();
                            broadcastToParty(snapshot, demoted);
                        }
                    }
                }
                case DISBANDED -> {
                    Component messageComponent;
                    if (actorId != null) {
                        messageComponent = Component.text()
                                .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                                .append(PartyTextFormatter.yellow(" has disbanded the party!"))
                                .build();
                    } else if ("empty-party-pruned".equals(reason)) {
                        messageComponent = PartyTextFormatter.yellow("The party was disbanded because all invites expired and the party was empty.");
                    } else {
                        messageComponent = PartyTextFormatter.yellow("Your party was disbanded.");
                    }
                    broadcastToParty(snapshot, messageComponent);
                }
                case RESERVATION_CREATED -> broadcastToParty(snapshot,
                        Component.text("Party reservation is pending. Sit tight for matchmaking...", NamedTextColor.AQUA));
                case RESERVATION_CLAIMED -> {
                    String reservationReason = message.getReason();
                    if (reservationReason != null && reservationReason.startsWith("reservation-failed")) {
                        String detail = reservationReason.substring("reservation-failed".length()).replaceFirst("^:", "");
                        Component text = Component.text("Party reservation failed", NamedTextColor.RED);
                        if (detail != null && !detail.isBlank()) {
                            text = text.append(Component.text(": " + detail, NamedTextColor.GRAY));
                        }
                        broadcastToParty(snapshot, text);
                    } else if ("reservation-missing-claims".equals(reservationReason)) {
                        broadcastToParty(snapshot,
                                Component.text("Party reservation expired before everyone joined.", NamedTextColor.YELLOW));
                    } else {
                        broadcastToParty(snapshot,
                                Component.text("Party reservation completed.", NamedTextColor.GREEN));
                    }
                }
                case UPDATED -> {
                    if ("member-disconnected".equals(reason)) {
                        long minutes = Math.max(1, PartyConstants.DISCONNECT_GRACE_SECONDS / 60);
                        Component disconnectNotice = Component.text()
                                .append(formatRankedName(actorId, safeName(snapshot, actorId)))
                                .append(PartyTextFormatter.yellow(" has disconnected, they have "))
                                .append(PartyTextFormatter.redNumber(minutes))
                                .append(PartyTextFormatter.yellow(" minutes to rejoin before they are removed from the party."))
                                .build();
                        broadcastToParty(snapshot, disconnectNotice);
                    }
                }
                default -> {
                    // no-op for other update types
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to handle party update message", ex);
        }
    }

    private void handleReservationCreatedEnvelope(MessageEnvelope envelope) {
        try {
            PartyReservationCreatedMessage message = convert(envelope.getPayload(), PartyReservationCreatedMessage.class);
            if (message == null) {
                return;
            }

            PartyReservationSnapshot reservation = message.getReservation();
            if (reservation == null) {
                return;
            }
            reservationTracker.trackReservation(reservation);

            String reservationId = reservation.getReservationId();
            if (reservationId == null || reservationId.isBlank()) {
                return;
            }

            Map<UUID, PartyReservationToken> tokens = reservation.getTokens();
            if (tokens == null || tokens.isEmpty()) {
                return;
            }

            String familyId = message.getFamilyId();
            if (familyId == null || familyId.isBlank()) {
                return;
            }
            String variantId = message.getVariantId();
            String targetServerId = reservation.getTargetServerId();
            long now = System.currentTimeMillis();

            tokens.forEach((playerId, token) -> proxy.getPlayer(playerId).ifPresent(player -> {
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("source", "party-reservation");
                metadata.put("requestedAt", Long.toString(now));
                if (familyId != null && !familyId.isBlank()) {
                    metadata.put("family", familyId);
                }
                if (variantId != null && !variantId.isBlank()) {
                    metadata.put("variant", variantId);
                }
                if (targetServerId != null && !targetServerId.isBlank()) {
                    metadata.put("targetServerId", targetServerId);
                }
                if (reservation.getPartyId() != null) {
                    metadata.put("partyId", reservation.getPartyId().toString());
                }
                metadata.put("partyReservationId", reservationId);
                metadata.put("partyTokenId", token.getTokenId());
                routingFeature.sendSlotRequest(player, familyId, metadata);
                sendFramed(player, Component.text(
                        "Queued party for " + displayVariant(familyId, variantId) + ".",
                        NamedTextColor.GREEN));
            }));
        } catch (Exception ex) {
            logger.warn("Failed to handle party reservation broadcast", ex);
        }
    }

    private void handleReservationClaimedEnvelope(MessageEnvelope envelope) {
        try {
            PartyReservationClaimedMessage message = convert(envelope.getPayload(), PartyReservationClaimedMessage.class);
            if (message == null) {
                return;
            }

            reservationTracker.recordClaim(message).ifPresent(completion -> {
                String reservationId = completion.reservationId();
                reservationStore.delete(reservationId);
                reservationTracker.forget(reservationId);

                boolean allSuccessful = completion.allSuccessful();
                String reason;
                if (!completion.failedPlayers().isEmpty()) {
                    reason = "reservation-failed:" + completion.failedPlayers().values().stream()
                            .findFirst()
                            .orElse("unknown");
                } else if (!completion.missingPlayers().isEmpty()) {
                    reason = "reservation-missing-claims";
                } else {
                    reason = "reservation-fulfilled";
                }

                PartyOperationResult cleared = partyService.clearActiveReservation(
                        completion.partyId(),
                        reservationId,
                        allSuccessful,
                        reason
                );
                if (!cleared.isSuccess()) {
                    logger.warn("Failed to clear active reservation {} for party {}",
                            reservationId, completion.partyId());
                } else {
                    logger.info("Cleared active reservation {} for party {} (success={})",
                            reservationId, completion.partyId(), allSuccessful);
                }
            });
        } catch (Exception ex) {
            logger.warn("Failed to handle party reservation claim broadcast", ex);
        }
    }

    private void handleMatchRosterEnvelope(MessageEnvelope envelope) {
        try {
            MatchRosterCreatedMessage message = convert(envelope.getPayload(), MatchRosterCreatedMessage.class);
            if (message == null || rosterStore == null) {
                return;
            }
            String slotId = message.getSlotId();
            if (slotId == null || slotId.isBlank()) {
                return;
            }
            Set<UUID> players = message.getPlayers();
            if (players == null || players.isEmpty()) {
                return;
            }
            rosterStore.registerRoster(slotId, message.getMatchId(), players);
        } catch (Exception ex) {
            logger.warn("Failed to handle match roster message", ex);
        }
    }

    private void handleMatchRosterEndedEnvelope(MessageEnvelope envelope) {
        try {
            MatchRosterEndedMessage message = convert(envelope.getPayload(), MatchRosterEndedMessage.class);
            if (message == null || rosterStore == null) {
                return;
            }
            String slotId = message.getSlotId();
            if (slotId == null || slotId.isBlank()) {
                return;
            }
            rosterStore.registerRoster(slotId, message.getMatchId(), Set.of());
        } catch (Exception ex) {
            logger.warn("Failed to handle match roster end message", ex);
        }
    }

    private void broadcastToParty(PartySnapshot snapshot, Component message) {
        snapshot.getMembers().keySet().forEach(memberId -> notifyPlayer(memberId, message));
    }

    private Component buildInviteBroadcast(PartySnapshot snapshot, PartyUpdateMessage message) {
        UUID inviterId = message.getActorPlayerId();
        UUID targetId = message.getTargetPlayerId();

        String inviterName = safeName(snapshot, inviterId);
        Component inviterDisplay = formatRankedName(inviterId, inviterName);

        long ttlSeconds = PartyConstants.INVITE_TTL_SECONDS;

        String targetName = safeName(snapshot, targetId);
        if ("Unknown".equals(targetName) && snapshot.getInvites().containsKey(targetId)) {
            PartyInvite invite = snapshot.getInvites().get(targetId);
            if (invite != null && invite.getTargetUsername() != null) {
                targetName = invite.getTargetUsername();
            }
        }
        Component targetDisplay = formatRankedName(targetId, targetName);

        return Component.text()
                .append(inviterDisplay)
                .append(PartyTextFormatter.yellow(" invited "))
                .append(targetDisplay)
                .append(PartyTextFormatter.yellow(" to the party! They have "))
                .append(PartyTextFormatter.redNumber(ttlSeconds))
                .append(PartyTextFormatter.yellow(" seconds to accept."))
                .build();
    }

    private void notifyPlayer(UUID playerId, Component message) {
        if (playerId == null || message == null || !localPlayers.contains(playerId)) {
            return;
        }
        proxy.getPlayer(playerId).ifPresent(player -> sendFramed(player, message));
    }

    private String safeName(PartySnapshot snapshot, UUID playerId) {
        if (playerId == null) {
            return "Unknown";
        }
        PartyMember member = snapshot.getMember(playerId);
        if (member != null) {
            return member.getUsername();
        }
        PartyInvite invite = snapshot.getInvites().get(playerId);
        if (invite != null && invite.getTargetUsername() != null && !invite.getTargetUsername().isBlank()) {
            return invite.getTargetUsername();
        }
        return "Unknown";
    }

    private String resolveRemovedMemberName(PartySnapshot snapshot, PartyUpdateMessage message) {
        String name = safeName(snapshot, message.getTargetPlayerId());
        if (!"Unknown".equals(name)) {
            return name;
        }
        String extracted = extractNameFromReason(message.getReason());
        return extracted != null && !extracted.isBlank() ? extracted : name;
    }

    private String resolveLeavingMemberName(PartySnapshot snapshot, PartyUpdateMessage message) {
        String name = safeName(snapshot, message.getActorPlayerId());
        if (!"Unknown".equals(name)) {
            return name;
        }
        String extracted = extractNameFromReason(message.getReason());
        return extracted != null && !extracted.isBlank() ? extracted : name;
    }

    private String extractNameFromReason(String reason) {
        if (reason == null) {
            return null;
        }
        int separator = reason.indexOf(':');
        if (separator <= 0 || separator >= reason.length() - 1) {
            return null;
        }
        String prefix = reason.substring(0, separator);
        if (!Set.of("offline-kick", "offline-timeout", "invite-expired", "member-left").contains(prefix)) {
            return null;
        }
        return reason.substring(separator + 1);
    }

    private String messageContext(UUID partyId, String message) {
        return "[Party " + partyId.toString().substring(0, 8) + "] " + message;
    }

    private <T> T convert(Object payload, Class<T> type) {
        return mapper.convertValue(payload, type);
    }

    private String displayVariant(String familyId, String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return familyId != null ? familyId : "";
        }
        return (familyId != null ? familyId : "") + ":" + variantId;
    }

    private void sendFramed(Player player, Component... lines) {
        sendFramed(player, Arrays.asList(lines));
    }

    private void sendFramed(Player player, Collection<Component> lines) {
        player.sendMessage(FRAME_LINE);
        lines.forEach(player::sendMessage);
        player.sendMessage(FRAME_LINE);
    }

    private Component formatRankedName(UUID playerId, String fallbackName) {
        return PartyTextFormatter.formatName(playerId, fallbackName, dataAPI, sessionService, logger);
    }
}
