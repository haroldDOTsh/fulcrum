package sh.harold.fulcrum.distribution.launcher;

import java.util.Arrays;
import java.util.Locale;

enum SingleMachineTier {
    IN_MEMORY("in-memory", "embedded-in-memory-authority-stores"),
    SLIM("slim", "reduced-real-engine-bindings"),
    FULL_ENGINE("full-engine", "full-engine-compose-bindings");

    private final String id;
    private final String storageShape;

    SingleMachineTier(String id, String storageShape) {
        this.id = id;
        this.storageShape = storageShape;
    }

    String id() {
        return id;
    }

    String storageShape() {
        return storageShape;
    }

    static SingleMachineTier fromId(String id) {
        String normalized = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(tier -> tier.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown single-machine tier: " + id));
    }
}
