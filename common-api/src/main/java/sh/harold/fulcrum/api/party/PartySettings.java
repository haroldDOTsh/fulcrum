package sh.harold.fulcrum.api.party;

import java.io.Serializable;
import java.util.Objects;

/**
 * Party-level configuration flags.
 */
public final class PartySettings implements Serializable {
    private static final long serialVersionUID = 1L;

    private PartyInvitePrivacy invitePrivacy = PartyInvitePrivacy.PUBLIC;
    private boolean autoQueueAccept = false;
    private boolean requireModWarpConfirmation = true;
    private boolean requireInMatchWarpConfirmation = true;
    private boolean partyChatMuted = false;

    public static PartySettings defaults() {
        return new PartySettings();
    }

    public PartyInvitePrivacy getInvitePrivacy() {
        return invitePrivacy;
    }

    public void setInvitePrivacy(PartyInvitePrivacy invitePrivacy) {
        this.invitePrivacy = Objects.requireNonNullElse(invitePrivacy, PartyInvitePrivacy.PUBLIC);
    }

    public boolean isAutoQueueAccept() {
        return autoQueueAccept;
    }

    public void setAutoQueueAccept(boolean autoQueueAccept) {
        this.autoQueueAccept = autoQueueAccept;
    }

    public boolean isRequireModWarpConfirmation() {
        return requireModWarpConfirmation;
    }

    public void setRequireModWarpConfirmation(boolean requireModWarpConfirmation) {
        this.requireModWarpConfirmation = requireModWarpConfirmation;
    }

    public boolean isRequireInMatchWarpConfirmation() {
        return requireInMatchWarpConfirmation;
    }

    public void setRequireInMatchWarpConfirmation(boolean requireInMatchWarpConfirmation) {
        this.requireInMatchWarpConfirmation = requireInMatchWarpConfirmation;
    }

    public boolean isPartyChatMuted() {
        return partyChatMuted;
    }

    public void setPartyChatMuted(boolean partyChatMuted) {
        this.partyChatMuted = partyChatMuted;
    }
}
