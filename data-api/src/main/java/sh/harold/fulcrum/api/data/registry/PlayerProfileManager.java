package sh.harold.fulcrum.api.data.registry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerProfileManager {
    private static final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    private PlayerProfileManager() {
    }

    public static PlayerProfile load(UUID playerId) {
        var profile = new PlayerProfile(playerId);
        profile.loadAll();
        profiles.put(playerId, profile);
        return profile;
    }

    public static PlayerProfile get(UUID playerId) {
        return profiles.get(playerId);
    }

    public static boolean isLoaded(UUID playerId) {
        return profiles.containsKey(playerId);
    }

    public static void unload(UUID playerId) {
        var profile = profiles.remove(playerId);
        if (profile != null) profile.saveAll();
    }
}
