package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.common.settings.SettingLevel;

import java.util.Objects;

/**
 * Snapshot of the privacy ladder for a particular player.
 */
public record PrivacyProfile(
        SettingLevel partyInvites,
        SettingLevel friendInvites,
        SettingLevel directMessages
) {

    public static final PrivacyProfile DEFAULT = new PrivacyProfile(
            SettingLevel.LOW,
            SettingLevel.LOW,
            SettingLevel.LOW
    );

    public PrivacyProfile {
        partyInvites = Objects.requireNonNullElse(partyInvites, SettingLevel.LOW);
        friendInvites = Objects.requireNonNullElse(friendInvites, SettingLevel.LOW);
        directMessages = Objects.requireNonNullElse(directMessages, SettingLevel.LOW);
    }
}
