package sh.harold.fulcrum.api.chat.channel;

import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.UUID;

/**
 * Hook for direct messaging services to integrate with the chat channel pipeline.
 */
public interface DirectMessageBridge {

    /**
     * @return {@code true} if the bridge handled the chat event and the standard channel logic should stop.
     */
    boolean interceptChat(AsyncChatEvent event);

    /**
     * Invoked when a player switches their explicit /chat channel selection.
     */
    void handleChannelSwitch(UUID playerId);

    /**
     * Invoked when the player quits so bridges can clear cached state.
     */
    void handlePlayerQuit(UUID playerId);
}
