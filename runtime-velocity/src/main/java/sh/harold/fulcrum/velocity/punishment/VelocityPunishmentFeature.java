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
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

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
        PlayerPunishmentTracker tracker = trackers.get(playerId);
        if (tracker == null) {
            return;
        }
        tracker.pruneExpired();
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
        tracker.pruneExpired();
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
            tracker.pruneExpired();
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

    private String resolveLink(String key, String fallback) {
        try {
            NetworkProfileView view = networkConfigService.getActiveProfile();
            return view.getValue("punishmentLinks." + key, String.class).orElse(fallback);
        } catch (Exception ex) {
            return fallback;
        }
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
        }

        void markStatus(UUID punishmentId, PunishmentStatus status) {
            if (status != PunishmentStatus.ACTIVE) {
                effectIndex.remove(punishmentId);
            }
        }

        void pruneExpired() {
            Instant now = Instant.now();
            effectIndex.values().forEach(list -> list.removeIf(effect -> effect.expiresAt() != null && effect.expiresAt().isBefore(now)));
            effectIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
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
