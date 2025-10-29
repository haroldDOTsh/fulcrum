package sh.harold.fulcrum.fundamentals.punishment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.network.NetworkProfileView;
import sh.harold.fulcrum.api.punishment.PunishmentEffectType;
import sh.harold.fulcrum.api.punishment.PunishmentReason;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime enforcement for mutes and local punishment awareness.
 */
public final class RuntimePunishmentFeature implements PluginFeature, Listener {

    private JavaPlugin plugin;
    private Logger logger;
    private MessageBus messageBus;
    private MessageHandler appliedHandler;
    private MessageHandler statusHandler;
    private RuntimePunishmentManager manager;

    @Override
    public int getPriority() {
        // After DataAPI (10) and before chat (65)
        return 64;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messageBus = container.getOptional(MessageBus.class).orElse(null);
        DataAPI dataAPI = container.getOptional(DataAPI.class).orElse(null);
        NetworkConfigService networkConfig = container.getOptional(NetworkConfigService.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(NetworkConfigService.class).orElse(null)
                        : null);

        this.manager = new RuntimePunishmentManager(plugin, logger, dataAPI, networkConfig);
        container.register(RuntimePunishmentManager.class, manager);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(RuntimePunishmentManager.class, manager));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        subscribeMessageBus();

        plugin.getServer().getOnlinePlayers().forEach(manager::handlePlayerJoin);
    }

    @Override
    public void shutdown() {
        unsubscribeMessageBus();
        if (manager != null) {
            manager.close();
        }
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(RuntimePunishmentManager.class));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    private void subscribeMessageBus() {
        if (messageBus == null) {
            logger.warning("MessageBus unavailable; runtime punishments will not receive updates.");
            return;
        }
        appliedHandler = this::handleAppliedMessage;
        statusHandler = this::handleStatusMessage;
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);
    }

    private void unsubscribeMessageBus() {
        if (messageBus == null) {
            return;
        }
        if (appliedHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
        }
        if (statusHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);
        }
    }

    private void handleAppliedMessage(MessageEnvelope envelope) {
        try {
            PunishmentAppliedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentAppliedMessage.class);
            if (message == null) {
                return;
            }
            manager.handleApplied(message);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process punishment applied message", ex);
        }
    }

    private void handleStatusMessage(MessageEnvelope envelope) {
        try {
            PunishmentStatusMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentStatusMessage.class);
            if (message == null) {
                return;
            }
            manager.handleStatus(message);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process punishment status message", ex);
        }
    }

    /**
     * Provides runtime mute lookups for chat enforcement.
     */
    public static final class RuntimePunishmentManager {
        private final JavaPlugin plugin;
        private final Logger logger;
        private final DataAPI dataAPI;
        private final NetworkConfigService networkConfig;
        private final ConcurrentMap<UUID, PlayerMuteTracker> trackers = new ConcurrentHashMap<>();

        RuntimePunishmentManager(JavaPlugin plugin,
                                 Logger logger,
                                 DataAPI dataAPI,
                                 NetworkConfigService networkConfig) {
            this.plugin = plugin;
            this.logger = logger;
            this.dataAPI = dataAPI;
            this.networkConfig = networkConfig;
        }

        void handlePlayerJoin(Player player) {
            if (dataAPI == null) {
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> hydrateFromSnapshot(player));
        }

        void handlePlayerQuit(UUID playerId) {
            trackers.remove(playerId);
        }

        void handleApplied(PunishmentAppliedMessage message) {
            UUID playerId = message.getPlayerId();
            if (playerId == null) {
                return;
            }
            PlayerMuteTracker tracker = trackers.computeIfAbsent(playerId, PlayerMuteTracker::new);
            tracker.apply(message);
            sendMuteReminderIfApplicable(playerId, tracker);
        }

        void handleStatus(PunishmentStatusMessage message) {
            UUID playerId = message.getPlayerId();
            UUID punishmentId = message.getPunishmentId();
            PunishmentStatus status = message.getStatus();
            if (playerId == null || punishmentId == null || status == null) {
                return;
            }
            PlayerMuteTracker tracker = trackers.get(playerId);
            if (tracker == null) {
                return;
            }
            tracker.markStatus(punishmentId, status);
            if (tracker.isEmpty()) {
                trackers.remove(playerId);
            }
        }

        public Optional<MuteEffect> activeMute(UUID playerId) {
            PlayerMuteTracker tracker = trackers.get(playerId);
            if (tracker == null) {
                return Optional.empty();
            }
            tracker.pruneExpired();
            MuteEffect mute = tracker.getActiveMute();
            if (mute == null) {
                trackers.remove(playerId, tracker);
                return Optional.empty();
            }
            return Optional.of(mute);
        }

        public void handleMutedChat(Player player) {
            PlayerMuteTracker tracker = trackers.computeIfAbsent(player.getUniqueId(), PlayerMuteTracker::new);
            tracker.pruneExpired();
            MuteEffect mute = tracker.getActiveMute();
            if (mute == null) {
                trackers.remove(player.getUniqueId(), tracker);
                return;
            }
            sendMuteReminder(player, mute);
        }

        private void hydrateFromSnapshot(Player player) {
            if (dataAPI == null) {
                return;
            }
            try {
                Document document = dataAPI.player(player.getUniqueId());
                if (document == null || !document.exists()) {
                    trackers.remove(player.getUniqueId());
                    return;
                }
                Object rawActive = document.get("punishments.activePunishments");
                if (!(rawActive instanceof List<?> list) || list.isEmpty()) {
                    trackers.remove(player.getUniqueId());
                    return;
                }
                PlayerMuteTracker tracker = trackers.computeIfAbsent(player.getUniqueId(), PlayerMuteTracker::new);
                tracker.replaceAll(list);
                tracker.pruneExpired();
                if (tracker.isEmpty()) {
                    trackers.remove(player.getUniqueId());
                } else {
                    sendMuteReminderIfApplicable(player.getUniqueId(), tracker);
                }
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to hydrate punishments for " + player.getUniqueId(), ex);
            }
        }

        private void sendMuteReminderIfApplicable(UUID playerId, PlayerMuteTracker tracker) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            Runnable task = () -> sendMuteReminder(player, tracker.getActiveMute());
            plugin.getServer().getScheduler().runTask(plugin, task);
        }

        private void sendMuteReminder(Player player, MuteEffect mute) {
            if (mute == null) {
                return;
            }
            Duration remaining = mute.expiresAt() != null ? Duration.between(Instant.now(), mute.expiresAt()) : null;
            String remainingText = remaining == null || remaining.isNegative() ? "Permanent" : formatDuration(remaining);
            String link = resolveLink("mute", "https://harold.sh/mutes");

            Component frame = Component.text("-----------------------------------------------------", NamedTextColor.RED)
                    .decorate(TextDecoration.STRIKETHROUGH);

            Component body = Component.text()
                    .append(frame).append(Component.newline())
                    .append(Component.text("You are currently muted for ", NamedTextColor.RED))
                    .append(Component.text(mute.message() != null ? mute.message() : mute.reason(), NamedTextColor.WHITE)).append(Component.newline())
                    .append(Component.text("Your mute will expire in ", NamedTextColor.GRAY))
                    .append(Component.text(remainingText, NamedTextColor.RED)).append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text("Find out more here: ", NamedTextColor.GRAY))
                    .append(Component.text(link, NamedTextColor.YELLOW)
                            .clickEvent(ClickEvent.openUrl(link)))
                    .append(Component.newline())
                    .append(Component.text("Mute ID: ", NamedTextColor.GRAY))
                    .append(Component.text("#" + mute.punishmentId().toString().split("-")[0].toUpperCase(Locale.ROOT), NamedTextColor.WHITE)).append(Component.newline())
                    .append(frame)
                    .build();

            player.sendMessage(body);
        }

        private String resolveLink(String key, String fallback) {
            try {
                if (networkConfig == null) {
                    return fallback;
                }
                NetworkProfileView profile = networkConfig.getActiveProfile();
                return profile.getValue("punishmentLinks." + key, String.class).orElse(fallback);
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

        void close() {
            trackers.clear();
        }

        private final class PlayerMuteTracker {
            private final UUID playerId;
            private final Map<UUID, MuteEffect> active = new ConcurrentHashMap<>();

            PlayerMuteTracker(UUID playerId) {
                this.playerId = playerId;
            }

            void apply(PunishmentAppliedMessage message) {
                UUID punishmentId = message.getPunishmentId();
                if (punishmentId == null) {
                    return;
                }
                for (PunishmentAppliedMessage.Effect effect : message.getEffects()) {
                    if (effect.getType() != PunishmentEffectType.MUTE) {
                        continue;
                    }
                    Instant expiresAt = effect.getExpiresAt();
                    String reason = message.getReason() != null ? message.getReason().getDisplayName() : "Mute";
                    String msg = effect.getMessage() != null ? effect.getMessage() : reason;
                    active.put(punishmentId, new MuteEffect(punishmentId, expiresAt, reason, msg));
                }
            }

            void markStatus(UUID punishmentId, PunishmentStatus status) {
                if (status != PunishmentStatus.ACTIVE) {
                    active.remove(punishmentId);
                }
            }

            void replaceAll(List<?> payloads) {
                active.clear();
                for (Object entry : payloads) {
                    if (!(entry instanceof Map<?, ?> map)) {
                        continue;
                    }
                    try {
                        UUID punishmentId = UUID.fromString(String.valueOf(map.get("punishmentId")));
                        PunishmentEffectType type = PunishmentEffectType.valueOf(String.valueOf(map.get("type")));
                        if (type != PunishmentEffectType.MUTE) {
                            continue;
                        }
                        Instant expiresAt = map.get("expiresAt") != null ? Instant.parse(String.valueOf(map.get("expiresAt"))) : null;
                        String reasonId = map.get("reason") != null ? String.valueOf(map.get("reason")) : null;
                        PunishmentReason reason = reasonId != null ? PunishmentReason.fromId(reasonId) : null;
                        String reasonDisplay = reason != null ? reason.getDisplayName() : (reasonId != null ? reasonId : "Mute");
                        String messageText = map.get("message") != null ? String.valueOf(map.get("message")) : reasonDisplay;
                        active.put(punishmentId, new MuteEffect(punishmentId, expiresAt, reasonDisplay, messageText));
                    } catch (Exception ignored) {
                    }
                }
            }

            void pruneExpired() {
                Instant now = Instant.now();
                active.values().removeIf(effect -> effect.expiresAt() != null && effect.expiresAt().isBefore(now));
            }

            MuteEffect getActiveMute() {
                Instant now = Instant.now();
                return active.values().stream()
                        .filter(effect -> effect.expiresAt() == null || effect.expiresAt().isAfter(now))
                        .findFirst()
                        .orElse(null);
            }

            boolean isEmpty() {
                return active.isEmpty();
            }
        }
    }

    public record MuteEffect(UUID punishmentId, Instant expiresAt, String reason, String message) {
    }
}
