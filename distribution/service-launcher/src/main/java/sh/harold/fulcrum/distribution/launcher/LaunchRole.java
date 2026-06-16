package sh.harold.fulcrum.distribution.launcher;

import java.util.Arrays;
import java.util.Locale;

enum LaunchRole {
    AUTHORITY_SERVICE("authority-service"),
    CONTROLLER_SERVICE("controller-service"),
    WORKER_AGENT("worker-agent"),
    PAPER_AGENT("paper-agent"),
    VELOCITY_AGENT("velocity-agent"),
    ALL("all");

    private final String id;

    LaunchRole(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static LaunchRole fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown launch role: " + id));
    }
}
