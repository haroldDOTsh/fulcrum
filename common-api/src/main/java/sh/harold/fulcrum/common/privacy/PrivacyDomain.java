package sh.harold.fulcrum.common.privacy;

/**
 * Enumerates the built-in privacy domains exposed through {@link sh.harold.fulcrum.common.settings.PlayerSettingsService}.
 */
public enum PrivacyDomain {
    PARTY_INVITES("privacy.partyInvites"),
    FRIEND_INVITES("privacy.friendInvites"),
    DIRECT_MESSAGES("privacy.directMessages");

    private final String settingKey;

    PrivacyDomain(String settingKey) {
        this.settingKey = settingKey;
    }

    public String settingKey() {
        return settingKey;
    }
}
