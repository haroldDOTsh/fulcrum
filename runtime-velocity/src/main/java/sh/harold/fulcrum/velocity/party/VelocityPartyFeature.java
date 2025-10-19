package sh.harold.fulcrum.velocity.party;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
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
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.session.PlayerSessionRecord;
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
            .decorate(TextDecoration.STRIKETHROUGH, true);
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final Map<String, RankVisual> RANK_VISUAL_OVERRIDES = Map.ofEntries(
            Map.entry("MVP_PLUS_PLUS", new RankVisual("&6[MVP++]", NamedTextColor.GOLD)),
            Map.entry("MVP_PLUS", new RankVisual("&b[MVP+]", NamedTextColor.AQUA)),
            Map.entry("MVP", new RankVisual("&b[MVP]", NamedTextColor.AQUA)),
            Map.entry("VIP_PLUS", new RankVisual("&a[VIP+]", NamedTextColor.GREEN)),
            Map.entry("VIP", new RankVisual("&a[VIP]", NamedTextColor.GREEN))
    );

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
        PartyCommand command = new PartyCommand(partyService, reservationService, proxy, routingFeature, rosterStore);
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
            switch (message.getAction()) {
                case INVITE_SENT -> {
                    Component broadcast = buildInviteBroadcast(snapshot, message);
                    broadcastToParty(snapshot, broadcast);
                    String inviterName = safeName(snapshot, message.getActorPlayerId());
                    notifyPlayer(message.getTargetPlayerId(), Component.text(messageContext(snapshot.getPartyId(),
                                    "You have been invited to a party by "
                                            + inviterName
                                            + ". Use /party accept "
                                            + inviterName + " to join."),
                            NamedTextColor.AQUA));
                }
                case INVITE_ACCEPTED -> broadcastToParty(snapshot,
                        Component.text(safeName(snapshot, message.getTargetPlayerId()) + " joined the party.", NamedTextColor.GREEN));
                case INVITE_REVOKED, INVITE_EXPIRED -> notifyPlayer(message.getTargetPlayerId(),
                        Component.text("Your party invite expired.", NamedTextColor.RED));
                case MEMBER_LEFT -> broadcastToParty(snapshot,
                        Component.text(safeName(snapshot, message.getActorPlayerId()) + " left the party.", NamedTextColor.YELLOW));
                case MEMBER_KICKED -> {
                    String removedName = resolveRemovedMemberName(snapshot, message);
                    broadcastToParty(snapshot,
                            Component.text(removedName + " was removed from the party.", NamedTextColor.RED));
                    notifyPlayer(message.getTargetPlayerId(),
                            Component.text("You were removed from the party.", NamedTextColor.RED));
                }
                case TRANSFERRED -> broadcastToParty(snapshot,
                        Component.text("Party leadership transferred to " + safeName(snapshot, snapshot.getLeaderId()) + ".", NamedTextColor.GOLD));
                case DISBANDED -> broadcastToParty(snapshot,
                        Component.text("Your party was disbanded.", NamedTextColor.YELLOW));
                case RESERVATION_CREATED -> broadcastToParty(snapshot,
                        Component.text("Party reservation is pending. Sit tight for matchmaking...", NamedTextColor.AQUA));
                case RESERVATION_CLAIMED -> {
                    String reason = message.getReason();
                    if (reason != null && reason.startsWith("reservation-failed")) {
                        String detail = reason.substring("reservation-failed".length()).replaceFirst("^:", "");
                        Component text = Component.text("Party reservation failed", NamedTextColor.RED);
                        if (detail != null && !detail.isBlank()) {
                            text = text.append(Component.text(": " + detail, NamedTextColor.GRAY));
                        }
                        broadcastToParty(snapshot, text);
                    } else if ("reservation-missing-claims".equals(reason)) {
                        broadcastToParty(snapshot,
                                Component.text("Party reservation expired before everyone joined.", NamedTextColor.YELLOW));
                    } else {
                        broadcastToParty(snapshot,
                                Component.text("Party reservation completed.", NamedTextColor.GREEN));
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
        Component inviterDisplay = formatRankedName(snapshot, inviterId, inviterName);
        Component targetDisplay = formatRankedName(snapshot, targetId, safeName(snapshot, targetId));

        long ttlSeconds = PartyConstants.INVITE_TTL_SECONDS;

        Component clickHere = Component.text("Click here to join", NamedTextColor.GOLD);
        if (!"Unknown".equalsIgnoreCase(inviterName)) {
            clickHere = clickHere
                    .clickEvent(ClickEvent.runCommand("/party accept " + inviterName))
                    .hoverEvent(HoverEvent.showText(Component.text("Join the party", NamedTextColor.YELLOW)));
        }

        return Component.text()
                .append(inviterDisplay)
                .append(Component.text(" invited ", NamedTextColor.YELLOW))
                .append(targetDisplay)
                .append(Component.text(" to the party! They have ", NamedTextColor.YELLOW))
                .append(Component.text(String.valueOf(ttlSeconds), NamedTextColor.RED))
                .append(Component.text(" seconds to accept. ", NamedTextColor.YELLOW))
                .append(clickHere)
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

    private String extractNameFromReason(String reason) {
        if (reason == null) {
            return null;
        }
        int separator = reason.indexOf(':');
        if (separator <= 0 || separator >= reason.length() - 1) {
            return null;
        }
        String prefix = reason.substring(0, separator);
        if (!"offline-kick".equals(prefix) && !"offline-timeout".equals(prefix)) {
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

    private Component formatRankedName(PartySnapshot snapshot, UUID playerId, String fallbackName) {
        RankVisual visual = resolveRankVisual(playerId);
        Component prefix = deserializeLegacy(visual.prefixLegacy());
        Component nameComponent = Component.text(fallbackName, visual.nameColor());
        if (prefix != null && !prefix.equals(Component.empty())) {
            return Component.text().append(prefix).append(Component.text(" ")).append(nameComponent).build();
        }
        return nameComponent;
    }

    private RankVisual resolveRankVisual(UUID playerId) {
        Map<String, Object> rankInfo = new LinkedHashMap<>();

        if (dataAPI != null) {
            try {
                Document doc = dataAPI.collection("players").document(playerId.toString());
                if (doc.exists()) {
                    Object raw = doc.get("rankInfo");
                    if (raw instanceof Map<?, ?> map) {
                        map.forEach((k, v) -> rankInfo.put(String.valueOf(k), v));
                    }
                }
            } catch (Exception ex) {
                logger.debug("Failed to read stored rank info for {}", playerId, ex);
            }
        }

        if (sessionService != null) {
            sessionService.getSession(playerId)
                    .map(PlayerSessionRecord::getRank)
                    .ifPresent(map -> map.forEach((k, v) -> rankInfo.put(String.valueOf(k), v)));
        }

        String prefixLegacy = firstNonBlank(
                asString(rankInfo.get("fullPrefix")),
                asString(rankInfo.get("prefix")),
                asString(rankInfo.get("shortPrefix")),
                asString(rankInfo.get("display")),
                asString(rankInfo.get("formatted"))
        );

        NamedTextColor color = resolveColor(rankInfo);
        String rankId = asString(rankInfo.get("primary"));

        if (rankId == null && dataAPI != null) {
            try {
                Document doc = dataAPI.collection("players").document(playerId.toString());
                if (doc.exists()) {
                    rankId = asString(doc.get("rankInfo.primary"));
                }
            } catch (Exception ex) {
                logger.debug("Failed to resolve stored primary rank for {}", playerId, ex);
            }
        }

        RankVisual override = rankId != null ? RANK_VISUAL_OVERRIDES.get(normalizeRankId(rankId)) : null;
        if (prefixLegacy == null && override != null) {
            prefixLegacy = override.prefixLegacy();
        }
        if (color == null && override != null) {
            color = override.nameColor();
        }

        if (prefixLegacy == null) {
            Rank enumRank = rankId != null ? parseRank(rankId) : null;
            if (enumRank != null) {
                prefixLegacy = enumRank.getShortPrefix();
                if (color == null) {
                    color = enumRank.getNameColor();
                }
            }
        }

        if (color == null) {
            color = NamedTextColor.YELLOW;
        }

        return new RankVisual(prefixLegacy, color);
    }

    private NamedTextColor resolveColor(Map<String, Object> rankInfo) {
        Object rawColor = rankInfo.get("nameColor");
        if (rawColor instanceof String colorName && !colorName.isBlank()) {
            NamedTextColor named = NamedTextColor.NAMES.value(colorName.toLowerCase());
            return named;
        }
        return null;
    }

    private Rank parseRank(String rankId) {
        if (rankId == null) {
            return null;
        }
        String normalized = normalizeRankId(rankId);
        try {
            return Rank.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizeRankId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replace(' ', '_')
                .replace('-', '_');
        normalized = normalized.replace("++", "_PLUS_PLUS");
        normalized = normalized.replace("+", "_PLUS");
        return normalized.toUpperCase();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Component deserializeLegacy(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return Component.empty();
        }
        return LEGACY_SERIALIZER.deserialize(legacy);
    }

    private record RankVisual(String prefixLegacy, NamedTextColor nameColor) {
        RankVisual {
            // default handled by caller
        }
    }
}
