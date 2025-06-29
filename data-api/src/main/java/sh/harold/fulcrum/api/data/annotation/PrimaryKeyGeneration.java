package sh.harold.fulcrum.api.data.annotation;

public enum PrimaryKeyGeneration {
    NONE,           // Developer manually sets ID (default)
    PLAYER_UUID,    // Use player UUID passed into .save(uuid, data)
    RANDOM_UUID     // Use UUID.randomUUID()
}
