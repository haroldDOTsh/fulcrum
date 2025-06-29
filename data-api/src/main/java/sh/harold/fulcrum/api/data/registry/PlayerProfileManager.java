package sh.harold.fulcrum.api.data.registry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerProfileManager {
    private static final Logger LOGGER = Logger.getLogger(PlayerProfileManager.class.getName());
    private static final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    private PlayerProfileManager() {
    }

    public static PlayerProfile load(UUID playerId) {
        if (isLoaded(playerId)) {
            return get(playerId);
        }

        PlayerProfile profile = new PlayerProfile(playerId);
        profile.loadAll();

        if (profile.isNew()) {
            LOGGER.info("New player profile detected for " + playerId + ". Saving initial data.");
            profile.saveAll();
        }

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
        if (profile != null) {
            LOGGER.info("Unloading and saving player profile for " + playerId + ".");
            profile.saveAll();
        }
    }
}
