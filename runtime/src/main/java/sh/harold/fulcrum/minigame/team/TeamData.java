package sh.harold.fulcrum.minigame.team;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight metadata container attached to a match team.
 */
public final class TeamData {

    private final ConcurrentHashMap<String, Object> values = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public boolean getBoolean(String key) {
        Object value = values.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return false;
    }

    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }
}
