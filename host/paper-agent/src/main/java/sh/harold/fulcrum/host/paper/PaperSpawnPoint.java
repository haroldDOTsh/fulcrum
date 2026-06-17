package sh.harold.fulcrum.host.paper;

import java.util.Objects;

public record PaperSpawnPoint(
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {
    public PaperSpawnPoint {
        worldName = PaperArtifactNames.requireNonBlank(worldName, "worldName");
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
    }

    public int bedrockBlockX() {
        return (int) Math.floor(x);
    }

    public int bedrockBlockY() {
        return (int) Math.floor(y) - 1;
    }

    public int bedrockBlockZ() {
        return (int) Math.floor(z);
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(Objects.requireNonNull(label, "label") + " must be finite");
        }
    }
}
