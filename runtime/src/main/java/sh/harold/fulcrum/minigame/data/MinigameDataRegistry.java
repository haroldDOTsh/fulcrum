package sh.harold.fulcrum.minigame.data;

import java.util.Map;
import java.util.Optional;

/**
 * Registry that provisions Mongo-backed collections for minigame-specific player data.
 */
public interface MinigameDataRegistry {

    /**
     * Register (or retrieve) the collection dedicated to the given family, backed by the specified POJO type.
     */
    <T> MinigameCollection<T> register(String familyId, String collectionName, Class<T> pojoClass);

    /**
     * Retrieve a previously registered collection for the given family and type.
     */
    <T> Optional<MinigameCollection<T>> get(String familyId, Class<T> pojoClass);

    /**
     * Provision the default map-based collection for the family if it has not been registered yet.
     */
    MinigameCollection<Map<String, Object>> registerDefault(String familyId);
}
