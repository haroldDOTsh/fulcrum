package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.common.settings.SettingLevel;

import java.util.EnumSet;

public final class PrivacyDomainPresets {
    private PrivacyDomainPresets() {
    }

    public static PrivacyDomainConfig partyInvites() {
        return new PrivacyDomainConfig(
                SettingLevel.NONE,
                EnumSet.of(SettingLevel.NONE, SettingLevel.MEDIUM, SettingLevel.MAX),
                (level, context) -> {
                    if (context.eitherBlocks()) {
                        return PrivacyResult.deny("Party invites are blocked between you and this player.");
                    }
                    return switch (level) {
                        case NONE -> PrivacyResult.allow();
                        case MEDIUM -> context.targetFriendsWithActor()
                                ? PrivacyResult.allow()
                                : PrivacyResult.deny("That player only accepts invites from friends.");
                        case MAX -> PrivacyResult.deny("Only staff can invite that player right now.");
                        default -> PrivacyResult.deny("That player is not accepting party invites right now.");
                    };
                },
                "That player is not accepting party invites right now."
        );
    }

    public static PrivacyDomainConfig friendInvites() {
        return new PrivacyDomainConfig(
                SettingLevel.NONE,
                EnumSet.of(SettingLevel.NONE, SettingLevel.LOW, SettingLevel.MAX),
                (level, context) -> {
                    if (context.eitherBlocks()) {
                        return PrivacyResult.deny("Friend requests are blocked between you and this player.");
                    }
                    return switch (level) {
                        case NONE -> PrivacyResult.allow();
                        case LOW -> context.sharedParty()
                                ? PrivacyResult.allow()
                                : PrivacyResult.deny("That player only accepts requests from party members.");
                        case MAX -> PrivacyResult.deny("Only staff can add that player right now.");
                        default -> PrivacyResult.deny("That player is not accepting friend requests right now.");
                    };
                },
                "That player is not accepting friend requests right now."
        );
    }

    public static PrivacyDomainConfig directMessages(EnumSet<SettingLevel> supportedLevels) {
        EnumSet<SettingLevel> levels = supportedLevels == null || supportedLevels.isEmpty()
                ? EnumSet.of(SettingLevel.NONE, SettingLevel.MEDIUM, SettingLevel.HIGH, SettingLevel.MAX)
                : EnumSet.copyOf(supportedLevels);
        return new PrivacyDomainConfig(
                SettingLevel.NONE,
                levels,
                (level, context) -> {
                    if (context.eitherBlocks()) {
                        return PrivacyResult.deny("You have blocked this player or they have blocked you.");
                    }
                    return switch (level) {
                        case NONE -> PrivacyResult.allow();
                        case MEDIUM -> context.sharedParty()
                                ? PrivacyResult.allow()
                                : PrivacyResult.deny("That player only accepts messages from their party.");
                        case HIGH -> context.mutualFriends()
                                ? PrivacyResult.allow()
                                : PrivacyResult.deny("That player only accepts messages from friends.");
                        case MAX -> PrivacyResult.deny("Only staff can message that player right now.");
                        default -> PrivacyResult.deny("That player is not accepting private messages right now.");
                    };
                },
                "That player is not accepting private messages right now."
        );
    }
}
