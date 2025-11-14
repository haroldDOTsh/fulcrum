package sh.harold.fulcrum.api.messagebus.messages.social;

import sh.harold.fulcrum.api.messagebus.ChannelConstants;

/**
 * Specialized relation event used for invite lifecycle broadcasts.
 */
public final class FriendRequestEventMessage extends FriendRelationEventMessage {
    @Override
    public String getMessageType() {
        return ChannelConstants.SOCIAL_FRIEND_REQUESTS;
    }
}
