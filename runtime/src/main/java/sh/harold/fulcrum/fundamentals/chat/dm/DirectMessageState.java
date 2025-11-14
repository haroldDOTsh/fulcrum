package sh.harold.fulcrum.fundamentals.chat.dm;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of a player's direct-messaging preferences/state.
 */
public record DirectMessageState(
        UUID lastTargetId,
        String lastTargetName,
        UUID openTargetId,
        String openChannelId,
        Instant openChannelUpdated,
        Instant lastMutation
) {

    public DirectMessageState {
        if (openTargetId == null || openChannelId == null) {
            openChannelUpdated = null;
        }
    }

    public static DirectMessageState empty() {
        return new DirectMessageState(null, null, null, null, null, Instant.now());
    }

    public DirectMessageState withLastTarget(UUID targetId, String targetName, Instant now) {
        return new DirectMessageState(targetId, targetName, openTargetId, openChannelId, openChannelUpdated, Objects.requireNonNullElse(now, Instant.now()));
    }

    public DirectMessageState withOpenChannel(UUID targetId, String channelId, Instant now) {
        return new DirectMessageState(lastTargetId, lastTargetName, targetId, channelId, Objects.requireNonNullElse(now, Instant.now()), Objects.requireNonNullElse(now, Instant.now()));
    }

    public DirectMessageState withoutOpenChannel(Instant now) {
        return new DirectMessageState(lastTargetId, lastTargetName, null, null, null, Objects.requireNonNullElse(now, Instant.now()));
    }

    public boolean hasOpenChannel() {
        return openTargetId != null && openChannelId != null && openChannelUpdated != null;
    }
}
