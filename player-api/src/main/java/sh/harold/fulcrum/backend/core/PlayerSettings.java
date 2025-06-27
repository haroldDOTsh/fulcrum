package sh.harold.fulcrum.playerdata;

import sh.harold.fulcrum.util.SettingsWrapper;

import java.util.*;

/**
 * Schema class for player settings, supporting nested settings via SettingsWrapper.
 */
public final class PlayerSettings {
    private final Map<String, Object> settings;

    public PlayerSettings() {
        this.settings = new HashMap<>();
    }

    public PlayerSettings(Map<String, Object> settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /**
     * Returns a SettingsWrapper for dot-path access (e.g. "fairysouls.unlocked.1").
     */
    public SettingsWrapper getSettingsWrapper() {
        return new SettingsWrapper(settings);
    }

    /**
     * Replaces the internal settings map from a SettingsWrapper.
     */
    public void setSettingsWrapper(SettingsWrapper wrapper) {
        Map<String, Object> map = wrapper.toMap();
        settings.clear();
        settings.putAll(map);
    }

    /**
     * Direct access for power users.
     */
    public Map<String, Object> getSettingsMap() {
        return settings;
    }
}
