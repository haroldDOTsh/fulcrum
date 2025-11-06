package sh.harold.fulcrum.fundamentals.staff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.ServerStatusChangeMessage;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.message.Message;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Broadcasts registry-reported service state changes to staff chat.
 */
public final class ServiceStatusBroadcastFeature implements PluginFeature {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JavaPlugin plugin;
    private MessageBus messageBus;
    private MessageHandler statusHandler;
    private Logger logger;

    @Override
    public int getPriority() {
        // After lifecycle initialization so the message bus and identifier are available
        return 60;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messageBus = container.getOptional(MessageBus.class)
                .orElseThrow(() -> new IllegalStateException("MessageBus unavailable for ServiceStatusBroadcastFeature"));

        statusHandler = this::handleStatusChange;
        messageBus.subscribe(ChannelConstants.REGISTRY_STATUS_CHANGE, statusHandler);
        logger.info("[STAFF] ServiceStatusBroadcastFeature subscribed to registry status updates");
    }

    @Override
    public void shutdown() {
        if (messageBus != null && statusHandler != null) {
            messageBus.unsubscribe(ChannelConstants.REGISTRY_STATUS_CHANGE, statusHandler);
        }
    }

    private void handleStatusChange(MessageEnvelope envelope) {
        ServerStatusChangeMessage statusChange = convertPayload(envelope.payload());
        if (statusChange == null || statusChange.getServerId() == null) {
            return;
        }

        String nextState = statusChange.getNewStatus() != null
                ? statusChange.getNewStatus().name()
                : "UNKNOWN";

        Component payload = Message.debug("Service {arg0} now in {arg1}!",
                        statusChange.getServerId(),
                        nextState)
                .tag("debug")
                .skipTranslation()
                .component();

        sendStaffChannel(payload);
        Component consoleComponent = Component.text("Staff > ", NamedTextColor.AQUA).append(payload);
        plugin.getServer().getConsoleSender().sendMessage(consoleComponent);
    }

    private ServerStatusChangeMessage convertPayload(Object raw) {
        if (raw instanceof ServerStatusChangeMessage message) {
            return message;
        }
        if (raw instanceof JsonNode node) {
            return deserializeNode(node);
        }
        return null;
    }

    private ServerStatusChangeMessage deserializeNode(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, ServerStatusChangeMessage.class);
        } catch (Exception exception) {
            logger.warning("Failed to deserialize ServerStatusChangeMessage: " + exception.getMessage());
            return null;
        }
    }

    private void sendStaffChannel(Component component) {
        if (messageBus == null) {
            dispatchLocally(component);
            return;
        }
        ChatChannelMessage chat = new ChatChannelMessage();
        chat.setMessageId(UUID.randomUUID());
        chat.setChannelId("staff");
        chat.setSenderId(new UUID(0L, 0L));
        chat.setComponentJson(GsonComponentSerializer.gson().serialize(component));
        chat.setPlainText(PlainTextComponentSerializer.plainText().serialize(component));
        chat.setTimestamp(System.currentTimeMillis());
        messageBus.broadcast(ChannelConstants.CHAT_CHANNEL_MESSAGE, chat);
    }

    private void dispatchLocally(Component component) {
        Component payload = Component.text("Staff > ", NamedTextColor.AQUA).append(component);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("fulcrum.staff")) {
                player.sendMessage(payload);
            }
        }
    }
}
