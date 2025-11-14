package sh.harold.fulcrum.api.chat.channel;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage;

import java.util.Optional;
import java.util.UUID;

/**
 * Service exposing chat channel operations for the runtime.
 */
public interface ChatChannelService {

    ChatChannelRef getActiveChannel(UUID playerId);

    void switchChannel(Player player, ChatChannelType type);

    void quickSend(Player player, ChatChannelType type, String rawMessage);

    Optional<UUID> findPartyId(UUID playerId);

    void handleAsyncChat(AsyncChatEvent event);

    void handleIncomingMessage(ChatChannelMessage message);

    void handlePartyUpdate(PartyUpdateMessage message);

    void handlePlayerJoin(Player player);

    void handlePlayerQuit(UUID playerId);

    default void registerDirectMessageBridge(DirectMessageBridge bridge) {
        // optional implementation
    }
}
