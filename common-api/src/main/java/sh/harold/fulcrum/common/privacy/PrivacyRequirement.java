package sh.harold.fulcrum.common.privacy;

import sh.harold.fulcrum.common.settings.SettingLevel;

@FunctionalInterface
public interface PrivacyRequirement {

    /**
     * Evaluate whether an action should be allowed for the supplied tier.
     *
     * @param level   the effective level after registry fallback
     * @param context resolved context (friends, presence, staff flag, etc.)
     * @return the resulting decision
     */
    PrivacyResult evaluate(SettingLevel level, PrivacyContext context);
}
