package sh.harold.fulcrum.fundamentals.punishment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class PunishmentBroadcastFeature implements PluginFeature {

    private JavaPlugin plugin;
    private MessageBus messageBus;
    private MessageHandler appliedHandler;
    private MessageHandler statusHandler;
    private Logger logger;
    private DataAPI dataAPI;
    private PlayerSessionService sessionService;

    @Override
    public int getPriority() {
        return 55; // after message bus but before gameplay features
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dataAPI = container.getOptional(DataAPI.class).orElse(null);
        this.sessionService = container.getOptional(PlayerSessionService.class).orElse(null);
        Optional<MessageBus> bus = container.getOptional(MessageBus.class);
        if (bus.isEmpty()) {
            logger.warning("MessageBus unavailable; punishment broadcasts disabled.");
            return;
        }

        this.messageBus = bus.get();
        this.appliedHandler = this::handleApplied;
        this.statusHandler = this::handleStatus;
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);
        logger.info("PunishmentBroadcastFeature subscribed to registry events.");
    }

    @Override
    public void shutdown() {
        if (messageBus != null) {
            if (appliedHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_APPLIED, appliedHandler);
            }
            if (statusHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_PUNISHMENT_STATUS, statusHandler);
            }
        }
    }

    private void handleApplied(MessageEnvelope envelope) {
        try {
            PunishmentAppliedMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentAppliedMessage.class);
            if (message == null) {
                return;
            }
            String staffName = message.getStaffName() != null ? message.getStaffName() : "Unknown";
            String playerName = resolvePlayerName(message.getPlayerName(), message.getPlayerId());
            String rungInfo = "(" + message.getRungBefore() + " ➜ " + message.getRungAfter() + ")";
            String punishmentId = shortUuid(message.getPunishmentId());

            Component payload = Message.success("{arg0} Punished {arg1} for {arg2} {arg3}",
                            staffName,
                            playerName,
                            message.getReason().getDisplayName(),
                            rungInfo)
                    .builder()
                    .tag("halcyon")
                    .skipTranslation()
                    .component()
                    .append(Component.text(" (" + punishmentId + ")", NamedTextColor.DARK_GRAY));

            logger.info(PlainTextComponentSerializer.plainText().serialize(payload));
            broadcastToStaffChannel(payload);
        } catch (Exception ex) {
            logger.warning("Failed to handle punishment applied message: " + ex.getMessage());
        }
    }

    private void handleStatus(MessageEnvelope envelope) {
        try {
            PunishmentStatusMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PunishmentStatusMessage.class);
            if (message == null) {
                return;
            }
            if (message.getStatus() == PunishmentStatus.ACTIVE) {
                return;
            }
            Component payload = Message.info("Punishment {arg0} for player {arg1} is now {arg2}.",
                            shortUuid(message.getPunishmentId()),
                            resolvePlayerName(message.getPlayerName(), message.getPlayerId()),
                            message.getStatus())
                    .builder()
                    .tag("halcyon")
                    .skipTranslation()
                    .component();
            logger.info(PlainTextComponentSerializer.plainText().serialize(payload));
            broadcastToStaffChannel(payload);
        } catch (Exception ex) {
            logger.warning("Failed to handle punishment status message: " + ex.getMessage());
        }
    }

    private void broadcastToStaffChannel(Component component) {
        if (messageBus == null) {
            return;
        }
        ChatChannelMessage chat = new ChatChannelMessage();
        chat.setMessageId(UUID.randomUUID());
        chat.setChannelId("staff");
        chat.setSenderId(new UUID(0L, 0L));
        chat.setComponentJson(GsonComponentSerializer.gson().serialize(component));
        chat.setPlainText(PlainTextComponentSerializer.plainText().serialize(component));
        chat.setTimestamp(System.currentTimeMillis());
        try {
            messageBus.broadcast(ChannelConstants.CHAT_CHANNEL_MESSAGE, chat);
        } catch (Exception ex) {
            logger.warning("Failed to broadcast punishment notice to staff channel: " + ex.getMessage());
        }
    }

    private String resolvePlayerName(String provided, UUID id) {
        if (id == null) {
            return "UNKNOWN";
        }
        if (plugin != null) {
            Player online = plugin.getServer().getPlayer(id);
            if (online != null) {
                return online.getName();
            }
        }
        String sessionName = resolveFromSession(id);
        if (sessionName != null) {
            return sessionName;
        }
        String persistedName = resolveFromData(id);
        if (persistedName != null) {
            return persistedName;
        }
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return shortUuid(id);
    }

    private String resolveFromSession(UUID id) {
        if (sessionService == null) {
            return null;
        }
        try {
            Optional<PlayerSessionRecord> session = sessionService.getActiveSession(id);
            if (session.isPresent()) {
                Object username = session.get().getCore().get("username");
                if (username instanceof String name && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ex) {
            logger.warning("Failed to read session username for " + id + ": " + ex.getMessage());
        }
        return null;
    }

    private String resolveFromData(UUID id) {
        if (dataAPI == null) {
            return null;
        }
        try {
            Document document = dataAPI.player(id);
            if (document != null && document.exists()) {
                Object username = document.get("core.username");
                if (username instanceof String name && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ex) {
            logger.warning("Failed to resolve player name for " + id + ": " + ex.getMessage());
        }
        return null;
    }

    private String shortUuid(UUID uuid) {
        if (uuid == null) {
            return "UNKNOWN";
        }
        return uuid.toString().substring(0, 8).toUpperCase();
    }
}
