package sh.harold.fulcrum.velocity.punishment;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.DocumentPatch;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;
import sh.harold.fulcrum.api.punishment.PunishmentLadder;
import sh.harold.fulcrum.api.punishment.PunishmentReason;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.kyori.adventure.text.Component.text;

public final class VelocityPunishmentFeature implements VelocityFeature {

    private final Map<UUID, PlayerPunishmentTracker> trackers = new ConcurrentHashMap<>();

    private ProxyServer proxy;
    private FulcrumVelocityPlugin plugin;
    private MessageBus messageBus;
    private Logger logger;
    private NetworkConfigService networkConfigService;
    private DataAPI dataAPI;
    private PostgresConnectionAdapter postgresAdapter;

    private MessageHandler appliedHandler;
    private MessageHandler statusHandler;

    @Override
    public String getName() {
        return "Punishments";
    }

    @Override
    public int getPriority() {
        return 90; // after network config but before commands
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.logger = logger;
        this.proxy = serviceLocator.getRequiredService(ProxyServer.class);
        this.plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.messageBus = serviceLocator.getRequiredService(MessageBus.class);
        this.networkConfigService = serviceLocator.getRequiredService(NetworkConfigService.class);
        this.dataAPI = serviceLocator.getRequiredService(DataAPI.class);
        this.postgresAdapter = serviceLocator.getService(PostgresConnectionAdapter.class).orElse(null);
        if (postgresAdapter == null) {
            logger.warn("PostgreSQL adapter unavailable; punishment expirations will not update relational ladder state.");
        }

        this.appliedHandler = this::handleApplied;
        this.statusHandler = this::handleStatus;
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);

        proxy.getEventManager().register(plugin, this);
        logger.info("VelocityPunishmentFeature initialised");
    }

    @Override
    public void shutdown() {
        proxy.getEventManager().unregisterListener(plugin, this);
        if (messageBus != null) {
            if (appliedHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
            }
            if (statusHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);
            }
        }
        trackers.clear();
    }

    private void handleApplied(MessageEnvelope envelope) {
        try {
            PunishmentAppliedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentAppliedMessage.class);
            if (message == null) {
                return;
            }
            applyPunishment(message);
        } catch (Exception ex) {
            logger.warn("Failed to process punishment applied message", ex);
        }
    }

    private void handleStatus(MessageEnvelope envelope) {
        try {
            PunishmentStatusMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentStatusMessage.class);
            if (message == null) {
                return;
            }
            updateStatus(message);
        } catch (Exception ex) {
            logger.warn("Failed to process punishment status message", ex);
        }
    }

    private PlayerPunishmentTracker hydrateFromSnapshot(UUID playerId) {
        Document document;
        try {
            document = dataAPI.player(playerId);
        } catch (Exception ex) {
            logger.warn("Failed to fetch punishment snapshot for {}", playerId, ex);
            trackers.remove(playerId);
            return null;
        }
        if (document == null || !document.exists()) {
            trackers.remove(playerId);
            return null;
        }

        Object raw = document.get("punishments.activePunishments");
        Instant now = Instant.now();
        Map<UUID, PunishmentLadder> expiredPunishments = new LinkedHashMap<>();
        Map<UUID, PunishmentLadder> activeLadders = new HashMap<>();
        List<Map<String, Object>> retainedEntries = new ArrayList<>();
        List<PunishmentEffectInstance> effects = new ArrayList<>();

        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                UUID punishmentId = parseUuid(stringValue(map.get("punishmentId")));
                PunishmentEffectType type = parseEffectType(stringValue(map.get("type")));
                if (punishmentId == null || type == null) {
                    continue;
                }

                PunishmentLadder ladderEnum = parseLadder(stringValue(map.get("ladder")));
                Instant expiresAt = parseInstant(stringValue(map.get("expiresAt")));
                boolean expired = expiresAt != null && expiresAt.isBefore(now) && isEnforcement(type);
                if (expired) {
                    if (ladderEnum != null) {
                        expiredPunishments.put(punishmentId, ladderEnum);
                    }
                    continue;
                }

                String reasonId = stringValue(map.get("reason"));
                String ladder = stringValue(map.get("ladder"));
                String issuedAt = stringValue(map.get("issuedAt"));
                String message = stringValue(map.get("message"));

                retainedEntries.add(copyActiveEntry(punishmentId, type, reasonId, ladder, issuedAt, expiresAt, message));
                effects.add(new PunishmentEffectInstance(
                        punishmentId,
                        type,
                        expiresAt,
                        message != null ? message : resolveReasonDisplay(reasonId)
                ));
                if (ladderEnum != null) {
                    activeLadders.put(punishmentId, ladderEnum);
                }
            }
        }

        if (!expiredPunishments.isEmpty()) {
            LinkedHashSet<String> history = extractHistory(document);
            expiredPunishments.keySet().stream().map(UUID::toString).forEach(history::add);
            applySnapshotUpdate(playerId, document, retainedEntries, history);
            markRelationalExpired(playerId, expiredPunishments);
        }

        if (effects.isEmpty()) {
            trackers.remove(playerId);
            return null;
        }

        PlayerPunishmentTracker tracker = trackers.computeIfAbsent(playerId, PlayerPunishmentTracker::new);
        tracker.replaceAll(effects, activeLadders);
        return tracker;
    }

    private void applyPunishment(PunishmentAppliedMessage message) {
        PlayerPunishmentTracker tracker = trackers.computeIfAbsent(message.getPlayerId(), PlayerPunishmentTracker::new);
        tracker.apply(message);

        if (tracker.hasActiveBan()) {
            proxy.getPlayer(message.getPlayerId()).ifPresent(player ->
                    player.disconnect(buildBanScreen(message, tracker.getActiveBanEffects())));
        } else {
            for (PunishmentAppliedMessage.Effect effect : message.getEffects()) {
                if (effect.getType() == PunishmentEffectType.MUTE) {
                    proxy.getPlayer(message.getPlayerId()).ifPresent(player ->
                            sendMuteReminder(player, message, new PunishmentEffectInstance(
                                    message.getPunishmentId(),
                                    effect.getType(),
                                    effect.getExpiresAt(),
                                    message.getReason().getDisplayName())));
                }
            }
        }
    }

    private void updateStatus(PunishmentStatusMessage message) {
        PlayerPunishmentTracker tracker = trackers.get(message.getPlayerId());
        if (tracker == null) {
            return;
        }
        tracker.markStatus(message.getPunishmentId(), message.getStatus());
        if (tracker.isEmpty()) {
            trackers.remove(message.getPlayerId());
        }
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PlayerPunishmentTracker tracker = hydrateFromSnapshot(playerId);
        if (tracker == null) {
            return;
        }
        Map<UUID, PunishmentLadder> expired = tracker.pruneExpired(Instant.now());
        if (!expired.isEmpty()) {
            synchronizeExpiredPunishments(playerId, expired);
            if (tracker.isEmpty()) {
                trackers.remove(playerId);
                return;
            }
        }
        if (tracker.hasActiveBan()) {
            Component screen = buildBanScreen(null, tracker.getActiveBanEffects());
            event.setResult(ResultedEvent.ComponentResult.denied(screen));
        }
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        PlayerPunishmentTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) {
            return;
        }
        Map<UUID, PunishmentLadder> expired = tracker.pruneExpired(Instant.now());
        if (!expired.isEmpty()) {
            synchronizeExpiredPunishments(player.getUniqueId(), expired);
            if (tracker.isEmpty()) {
                trackers.remove(player.getUniqueId());
                return;
            }
        }
        PunishmentEffectInstance mute = tracker.getActiveMuteEffect();
        if (mute == null) {
            return;
        }
        event.setResult(PlayerChatEvent.ChatResult.denied());
        sendMuteReminder(player, mute.message(), mute);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        PlayerPunishmentTracker tracker = trackers.get(event.getPlayer().getUniqueId());
        if (tracker != null) {
            Map<UUID, PunishmentLadder> expired = tracker.pruneExpired(Instant.now());
            if (!expired.isEmpty()) {
                synchronizeExpiredPunishments(event.getPlayer().getUniqueId(), expired);
            }
            if (tracker.isEmpty()) {
                trackers.remove(event.getPlayer().getUniqueId());
            }
        }
    }

    private Component buildBanScreen(PunishmentAppliedMessage message, List<PunishmentEffectInstance> effects) {
        if (effects == null || effects.isEmpty()) {
            return text("You are currently banned from this server.", NamedTextColor.RED);
        }
        PunishmentEffectInstance primary = effects.stream()
                .filter(e -> e.type() == PunishmentEffectType.BLACKLIST || e.type() == PunishmentEffectType.BAN)
                .findFirst()
                .orElse(effects.get(0));

        Duration remaining = primary.expiresAt() != null ? Duration.between(Instant.now(), primary.expiresAt()) : null;
        String durationText = remaining == null || remaining.isNegative()
                ? "Permanent"
                : formatDuration(remaining);

        String reasonLine;
        String ladderLine = "";
        if (message != null) {
            reasonLine = message.getReason().getDisplayName();
            ladderLine = message.getLadder().getDisplayName();
        } else {
            // fallback when invoked during login without message context
            reasonLine = primary.message();
        }

        String banLink = resolveLink("ban", "https://fulcrum.gg/appeals");
        Component frame = Component.text("-----------------------------------------------------", NamedTextColor.RED)
                .decorate(TextDecoration.STRIKETHROUGH);
        Component newline = Component.newline();

        Component header = text("You are temporarily banned for ", NamedTextColor.RED)
                .append(text(durationText, NamedTextColor.WHITE))
                .append(text(" from this server!", NamedTextColor.RED));
        if (primary.type() == PunishmentEffectType.BLACKLIST) {
            header = text("You are currently blocked from joining this server!", NamedTextColor.RED);
        } else if (remaining == null) {
            header = text("You are permanently banned from this server!", NamedTextColor.RED);
        }

        Component reasonComponent = text("Reason: ", NamedTextColor.GRAY)
                .append(text(reasonLine, NamedTextColor.WHITE));
        if (message != null) {
            reasonComponent = reasonComponent.append(text(" (" + ladderLine + ")", NamedTextColor.GRAY));
        }

        Component infoLink = text("Find out more: ", NamedTextColor.GRAY)
                .append(text(banLink, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(banLink))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(text("Open punishment info", NamedTextColor.AQUA))));

        String banId = primary.punishmentId().toString().split("-")[0].toUpperCase(Locale.ROOT);
        Component banIdLine = text("Ban ID: ", NamedTextColor.GRAY)
                .append(text("#" + banId, NamedTextColor.WHITE));

        Component warning = text("Sharing your Ban ID may affect the processing of your appeal!", NamedTextColor.RED);

        return Component.text()
                .append(frame).append(newline)
                .append(header).append(newline)
                .append(reasonComponent).append(newline)
                .append(infoLink).append(newline)
                .append(banIdLine).append(newline)
                .append(warning).append(newline)
                .append(frame)
                .build();
    }

    private void sendMuteReminder(Player player, PunishmentAppliedMessage message, PunishmentEffectInstance effect) {
        Component frame = Component.text("-----------------------------------------------------", NamedTextColor.RED)
                .decorate(TextDecoration.STRIKETHROUGH);
        Duration remaining = effect.expiresAt() != null ? Duration.between(Instant.now(), effect.expiresAt()) : null;
        String remainingText = remaining == null || remaining.isNegative() ? "Permanent" : formatDuration(remaining);
        String link = resolveLink("mute", "https://fulcrum.gg/mutes");

        Component body = Component.text()
                .append(frame).append(Component.newline())
                .append(text("You are currently muted for ", NamedTextColor.RED))
                .append(text(message.getReason().getDisplayName(), NamedTextColor.WHITE)).append(Component.newline())
                .append(text("Your mute will expire in ", NamedTextColor.GRAY))
                .append(text(remainingText, NamedTextColor.RED)).append(Component.newline())
                .append(text("Find out more here: ", NamedTextColor.GRAY))
                .append(text(link, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.openUrl(link))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(text("Open mute info", NamedTextColor.YELLOW))))
                .append(Component.newline())
                .append(text("Mute ID: ", NamedTextColor.GRAY))
                .append(text("#" + effect.punishmentId().toString().split("-")[0].toUpperCase(Locale.ROOT), NamedTextColor.WHITE)).append(Component.newline())
                .append(frame)
                .build();
        player.sendMessage(body);
    }

    private void sendMuteReminder(Player player, String reason, PunishmentEffectInstance effect) {
        Duration remaining = effect.expiresAt() != null ? Duration.between(Instant.now(), effect.expiresAt()) : null;
        String remainingText = remaining == null || remaining.isNegative() ? "Permanent" : formatDuration(remaining);
        String link = resolveLink("mute", "https://fulcrum.gg/mutes");
        Component frame = Component.text("-----------------------------------------------------", NamedTextColor.RED)
                .decorate(TextDecoration.STRIKETHROUGH);
        Component body = Component.text()
                .append(frame).append(Component.newline())
                .append(text("You are currently muted for ", NamedTextColor.RED))
                .append(text(reason, NamedTextColor.WHITE)).append(Component.newline())
                .append(text("Your mute will expire in ", NamedTextColor.GRAY))
                .append(text(remainingText, NamedTextColor.RED)).append(Component.newline())
                .append(text("Find out more here: ", NamedTextColor.GRAY))
                .append(text(link, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.openUrl(link)))
                .append(Component.newline())
                .append(text("Mute ID: ", NamedTextColor.GRAY))
                .append(text("#" + effect.punishmentId().toString().split("-")[0].toUpperCase(Locale.ROOT), NamedTextColor.WHITE)).append(Component.newline())
                .append(frame)
                .build();
        player.sendMessage(body);
    }

    private void synchronizeExpiredPunishments(UUID playerId, Map<UUID, PunishmentLadder> expiredPunishmentIds) {
        if (expiredPunishmentIds.isEmpty()) {
            return;
        }

        Document document;
        try {
            document = dataAPI.player(playerId);
        } catch (Exception ex) {
            logger.warn("Failed to fetch punishment snapshot for {} while pruning", playerId, ex);
            return;
        }

        if (document == null || !document.exists()) {
            return;
        }

        Object raw = document.get("punishments.activePunishments");
        List<Map<String, Object>> retainedEntries = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                UUID punishmentId = parseUuid(stringValue(map.get("punishmentId")));
                PunishmentEffectType type = parseEffectType(stringValue(map.get("type")));
                if (punishmentId == null || type == null) {
                    continue;
                }
                if (expiredPunishmentIds.containsKey(punishmentId) && isEnforcement(type)) {
                    continue;
                }
                String reasonId = stringValue(map.get("reason"));
                String ladder = stringValue(map.get("ladder"));
                String issuedAt = stringValue(map.get("issuedAt"));
                Instant expiresAt = parseInstant(stringValue(map.get("expiresAt")));
                String message = stringValue(map.get("message"));
                retainedEntries.add(copyActiveEntry(punishmentId, type, reasonId, ladder, issuedAt, expiresAt, message));
            }
        }

        LinkedHashSet<String> history = extractHistory(document);
        expiredPunishmentIds.keySet().stream().map(UUID::toString).forEach(history::add);

        applySnapshotUpdate(playerId, document, retainedEntries, history);
        markRelationalExpired(playerId, expiredPunishmentIds);
    }

    private void markRelationalExpired(UUID playerId, Map<UUID, PunishmentLadder> expiredPunishments) {
        if (postgresAdapter == null || expiredPunishments.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        String updateSql = "UPDATE punishments SET status = ?, updated_at = ? WHERE punishment_id = ? AND status <> ?";
        String countSql = "SELECT COUNT(*) FROM punishments WHERE player_uuid = ? AND ladder = ? AND status = ?";
        String upsertSql = "INSERT INTO ladder_state(player_uuid, ladder, rung, updated_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (player_uuid, ladder) DO UPDATE SET rung = EXCLUDED.rung, updated_at = EXCLUDED.updated_at";
        String deleteSql = "DELETE FROM ladder_state WHERE player_uuid = ? AND ladder = ?";

        try (Connection connection = postgresAdapter.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(updateSql);
                 PreparedStatement count = connection.prepareStatement(countSql);
                 PreparedStatement upsert = connection.prepareStatement(upsertSql);
                 PreparedStatement delete = connection.prepareStatement(deleteSql)) {

                for (Map.Entry<UUID, PunishmentLadder> entry : expiredPunishments.entrySet()) {
                    UUID punishmentId = entry.getKey();
                    PunishmentLadder ladder = entry.getValue();
                    if (ladder == null) {
                        continue;
                    }

                    update.setString(1, PunishmentStatus.INACTIVE.name());
                    update.setTimestamp(2, Timestamp.from(now));
                    update.setObject(3, punishmentId);
                    update.setString(4, PunishmentStatus.INACTIVE.name());
                    int rows = update.executeUpdate();
                    if (rows == 0) {
                        continue;
                    }

                    count.setObject(1, playerId);
                    count.setString(2, ladder.name());
                    count.setString(3, PunishmentStatus.ACTIVE.name());
                    int active = 0;
                    try (ResultSet rs = count.executeQuery()) {
                        if (rs.next()) {
                            active = rs.getInt(1);
                        }
                    }

                    if (active > 0) {
                        upsert.setObject(1, playerId);
                        upsert.setString(2, ladder.name());
                        upsert.setInt(3, active);
                        upsert.setTimestamp(4, Timestamp.from(now));
                        upsert.executeUpdate();
                    } else {
                        delete.setObject(1, playerId);
                        delete.setString(2, ladder.name());
                        delete.executeUpdate();
                    }
                }

                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                logger.warn("Failed to update relational punishment state for {}", playerId, ex);
            }
        } catch (SQLException ex) {
            logger.warn("Failed to acquire PostgreSQL connection for punishment expiry", ex);
        }
    }

    private void applySnapshotUpdate(UUID playerId,
                                     Document document,
                                     List<Map<String, Object>> activeEntries,
                                     Collection<String> historyEntries) {
        try {
            DocumentPatch patch = DocumentPatch.builder()
                    .upsert(true)
                    .set("punishments.activePunishments", new ArrayList<>(activeEntries))
                    .set("punishments.punishmentHistory", new ArrayList<>(historyEntries))
                    .set("punishments.lastSyncedAt", Instant.now().toString())
                    .build();
            document.patch(patch);
        } catch (Exception ex) {
            logger.warn("Failed to update punishment snapshot for {}", playerId, ex);
        }
    }

    private LinkedHashSet<String> extractHistory(Document document) {
        LinkedHashSet<String> history = new LinkedHashSet<>();
        Object rawHistory = document.get("punishments.punishmentHistory");
        if (rawHistory instanceof List<?> list) {
            for (Object value : list) {
                String text = stringValue(value);
                if (text != null) {
                    history.add(text);
                }
            }
        }
        return history;
    }

    private Map<String, Object> copyActiveEntry(UUID punishmentId,
                                                PunishmentEffectType type,
                                                String reasonId,
                                                String ladder,
                                                String issuedAt,
                                                Instant expiresAt,
                                                String message) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("punishmentId", punishmentId.toString());
        copy.put("type", type.name());
        if (reasonId != null) {
            copy.put("reason", reasonId);
        }
        if (ladder != null) {
            copy.put("ladder", ladder);
        }
        if (issuedAt != null) {
            copy.put("issuedAt", issuedAt);
        }
        copy.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
        if (message != null) {
            copy.put("message", message);
        }
        return copy;
    }

    private boolean isEnforcement(PunishmentEffectType type) {
        return type == PunishmentEffectType.BAN
                || type == PunishmentEffectType.BLACKLIST
                || type == PunishmentEffectType.MUTE;
    }

    private String resolveLink(String key, String fallback) {
        try {
            NetworkProfileView view = networkConfigService.getActiveProfile();
            return view.getValue("punishmentLinks." + key, String.class).orElse(fallback);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String resolveReasonDisplay(String reasonId) {
        if (reasonId == null || reasonId.isBlank()) {
            return "Unknown";
        }
        PunishmentReason reason = PunishmentReason.fromId(reasonId);
        return reason != null ? reason.getDisplayName() : reasonId;
    }

    private PunishmentEffectType parseEffectType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return PunishmentEffectType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private PunishmentLadder parseLadder(String ladder) {
        if (ladder == null || ladder.isBlank()) {
            return null;
        }
        try {
            return PunishmentLadder.valueOf(ladder);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "Permanent";
        }
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0 || builder.length() > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 || builder.length() > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(seconds).append("s");
        return builder.toString().trim();
    }

    private static final class PlayerPunishmentTracker {
        private final UUID playerId;
        private final Map<UUID, List<PunishmentEffectInstance>> effectIndex = new ConcurrentHashMap<>();
        private final Map<UUID, PunishmentLadder> ladders = new ConcurrentHashMap<>();

        PlayerPunishmentTracker(UUID playerId) {
            this.playerId = playerId;
        }

        void apply(PunishmentAppliedMessage message) {
            for (PunishmentAppliedMessage.Effect effect : message.getEffects()) {
                if (effect.getType() == PunishmentEffectType.WARNING || effect.getType() == PunishmentEffectType.APPEAL_REQUIRED
                        || effect.getType() == PunishmentEffectType.MANUAL_REVIEW) {
                    continue;
                }
                PunishmentEffectInstance instance = new PunishmentEffectInstance(
                        message.getPunishmentId(),
                        effect.getType(),
                        effect.getExpiresAt(),
                        effect.getMessage() != null ? effect.getMessage() : message.getReason().getDisplayName());
                effectIndex.computeIfAbsent(instance.punishmentId(), id -> new ArrayList<>()).add(instance);
            }
            if (message.getLadder() != null) {
                ladders.put(message.getPunishmentId(), message.getLadder());
            }
        }

        void markStatus(UUID punishmentId, PunishmentStatus status) {
            if (status != PunishmentStatus.ACTIVE) {
                effectIndex.remove(punishmentId);
                ladders.remove(punishmentId);
            }
        }

        void replaceAll(List<PunishmentEffectInstance> effects, Map<UUID, PunishmentLadder> ladderIndex) {
            effectIndex.clear();
            ladders.clear();
            for (PunishmentEffectInstance instance : effects) {
                effectIndex.computeIfAbsent(instance.punishmentId(), id -> new ArrayList<>()).add(instance);
            }
            ladders.putAll(ladderIndex);
        }

        Map<UUID, PunishmentLadder> pruneExpired(Instant now) {
            Map<UUID, PunishmentLadder> expired = new HashMap<>();
            Iterator<Map.Entry<UUID, List<PunishmentEffectInstance>>> iterator = effectIndex.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, List<PunishmentEffectInstance>> entry = iterator.next();
                List<PunishmentEffectInstance> effects = entry.getValue();
                effects.removeIf(effect -> effect.expiresAt() != null && effect.expiresAt().isBefore(now));
                if (effects.isEmpty()) {
                    iterator.remove();
                    PunishmentLadder ladder = ladders.remove(entry.getKey());
                    if (ladder != null) {
                        expired.put(entry.getKey(), ladder);
                    }
                }
            }
            return expired;
        }

        boolean hasActiveBan() {
            return getActiveBanEffects().stream().anyMatch(instance ->
                    instance.expiresAt() == null || instance.expiresAt().isAfter(Instant.now()));
        }

        boolean hasActiveMute() {
            PunishmentEffectInstance mute = getActiveMuteEffect();
            return mute != null && (mute.expiresAt() == null || mute.expiresAt().isAfter(Instant.now()));
        }

        List<PunishmentEffectInstance> getActiveBanEffects() {
            List<PunishmentEffectInstance> list = new ArrayList<>();
            for (List<PunishmentEffectInstance> effects : effectIndex.values()) {
                for (PunishmentEffectInstance instance : effects) {
                    if (instance.type() == PunishmentEffectType.BAN || instance.type() == PunishmentEffectType.BLACKLIST) {
                        list.add(instance);
                    }
                }
            }
            return list;
        }

        PunishmentEffectInstance getActiveMuteEffect() {
            return effectIndex.values().stream()
                    .flatMap(List::stream)
                    .filter(instance -> instance.type() == PunishmentEffectType.MUTE)
                    .findFirst()
                    .orElse(null);
        }

        boolean isEmpty() {
            return effectIndex.isEmpty();
        }
    }

    private record PunishmentEffectInstance(UUID punishmentId, PunishmentEffectType type, Instant expiresAt,
                                            String message) {
    }
}
