package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.common.settings.SettingLevel;

import java.util.EnumSet;
import java.util.Objects;

public final class PrivacyDomainConfig {
    private final SettingLevel defaultLevel;
    private final EnumSet<SettingLevel> supportedLevels;
    private final PrivacyRequirement requirement;
    private final String fallbackMessage;

    public PrivacyDomainConfig(SettingLevel defaultLevel,
                               EnumSet<SettingLevel> supportedLevels,
                               PrivacyRequirement requirement,
                               String fallbackMessage) {
        this.defaultLevel = Objects.requireNonNullElse(defaultLevel, SettingLevel.LOW);
        this.supportedLevels = supportedLevels == null || supportedLevels.isEmpty()
                ? EnumSet.of(this.defaultLevel)
                : EnumSet.copyOf(supportedLevels);
        this.requirement = Objects.requireNonNull(requirement, "requirement");
        this.supportedLevels.add(this.defaultLevel);
        this.fallbackMessage = fallbackMessage;
    }

    private static SettingLevel downgrade(SettingLevel level) {
        int previous = level.ordinal() - 1;
        return previous >= 0 ? SettingLevel.values()[previous] : null;
    }

    public SettingLevel defaultLevel() {
        return defaultLevel;
    }

    public EnumSet<SettingLevel> supportedLevels() {
        return EnumSet.copyOf(supportedLevels);
    }

    public PrivacyRequirement requirement() {
        return requirement;
    }

    public PrivacyResult fallbackDeny() {
        return fallbackMessage != null
                ? PrivacyResult.deny(fallbackMessage)
                : PrivacyResult.deny("That player is not accepting this action right now.");
    }

    public SettingLevel resolveLevel(SettingLevel requested) {
        SettingLevel candidate = requested != null ? requested : defaultLevel;
        while (candidate != null) {
            if (supportedLevels.contains(candidate)) {
                return candidate;
            }
            candidate = downgrade(candidate);
        }
        return defaultLevel;
    }
}
