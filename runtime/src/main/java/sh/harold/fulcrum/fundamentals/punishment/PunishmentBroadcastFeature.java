package sh.harold.fulcrum.fundamentals.punishment;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentAppliedMessage;
import sh.harold.fulcrum.api.messagebus.messages.punishment.PunishmentStatusMessage;
import sh.harold.fulcrum.api.punishment.PunishmentStatus;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class PunishmentBroadcastFeature implements PluginFeature {

    private MessageBus messageBus;
    private MessageHandler appliedHandler;
    private MessageHandler statusHandler;
    private Logger logger;

    @Override
    public int getPriority() {
        return 55; // after message bus but before gameplay features
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.logger = plugin.getLogger();
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
            String playerName = message.getPlayerName() != null ? message.getPlayerName() : message.getPlayerId().toString();
            String display = "[Punish] " + staffName + " applied " + message.getReason().getDisplayName()
                    + " (rung " + message.getRungAfter() + ") to " + playerName;

            logger.info(display);
            broadcastToStaffChannel(display);
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
            String display = "[Punish] Punishment " + message.getPunishmentId() + " for player "
                    + message.getPlayerId() + " is now " + message.getStatus();
            logger.info(display);
            broadcastToStaffChannel(display);
        } catch (Exception ex) {
            logger.warning("Failed to handle punishment status message: " + ex.getMessage());
        }
    }

    private void broadcastToStaffChannel(String message) {
        if (messageBus == null) {
            return;
        }
        ChatChannelMessage chat = new ChatChannelMessage();
        chat.setMessageId(UUID.randomUUID());
        chat.setChannelId("staff");
        chat.setSenderId(new UUID(0L, 0L));
        chat.setPlainText(message);
        chat.setTimestamp(System.currentTimeMillis());
        try {
            messageBus.broadcast(ChannelConstants.CHAT_CHANNEL_MESSAGE, chat);
        } catch (Exception ex) {
            logger.warning("Failed to broadcast punishment notice to staff channel: " + ex.getMessage());
        }
    }
}
