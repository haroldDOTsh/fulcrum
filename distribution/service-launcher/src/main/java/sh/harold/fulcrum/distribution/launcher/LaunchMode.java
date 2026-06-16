package sh.harold.fulcrum.distribution.launcher;

import java.util.Arrays;
import java.util.Locale;

enum LaunchMode {
    PLAN("plan"),
    RUN("run");

    private final String id;

    LaunchMode(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static LaunchMode fromId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown launcher mode: " + id));
    }
}
