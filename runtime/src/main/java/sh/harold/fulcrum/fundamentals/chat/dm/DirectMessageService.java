package sh.harold.fulcrum.fundamentals.chat.dm;

import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.chat.channel.DirectMessageBridge;
import sh.harold.fulcrum.api.messagebus.messages.social.DirectMessageEnvelope;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface DirectMessageService extends DirectMessageBridge {

    CompletionStage<DirectMessageResult> sendMessage(Player sender, String target, String message);

    CompletionStage<DirectMessageResult> openChannel(Player sender, String target);

    CompletionStage<DirectMessageResult> reply(Player sender, String message);

    void handlePlayerJoin(Player player);

    void handlePlayerQuit(UUID playerId);

    void deliverIncoming(DirectMessageEnvelope envelope);

    void shutdown();
}
