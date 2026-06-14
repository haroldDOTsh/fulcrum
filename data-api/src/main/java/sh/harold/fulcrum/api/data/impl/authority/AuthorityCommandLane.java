package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic writer-lane assignment for an authority command route.
 */
record AuthorityCommandLane(
    String domain,
    String partitionKey,
    int laneCount,
    int lane,
    String laneKeyFingerprint,
    String fencingScope
) {
    static final int DEFAULT_LANE_COUNT = 256;

    AuthorityCommandLane {
        domain = requireText(domain, "domain");
        partitionKey = requireText(partitionKey, "partitionKey");
        if (laneCount <= 0) {
            throw new IllegalArgumentException("laneCount must be positive");
        }
        if (lane < 0 || lane >= laneCount) {
            throw new IllegalArgumentException("lane must be within laneCount");
        }
        if (laneKeyFingerprint == null || !laneKeyFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("laneKeyFingerprint must be a SHA-256 hash");
        }
        fencingScope = requireText(fencingScope, "fencingScope");
    }

    static AuthorityCommandLane fromCommand(DataAuthority.AuthorityCommand command, int laneCount) {
        Objects.requireNonNull(command, "command");
        return fromRoute(AuthorityCommandRoute.fromCommand(command), laneCount);
    }

    static AuthorityCommandLane fromRoute(AuthorityCommandRoute route, int laneCount) {
        Objects.requireNonNull(route, "route");
        String material = route.domain() + "\n" + route.partitionKey();
        String fingerprint = AuthorityCommandFingerprints.hash(material);
        int assignedLane = lane(fingerprint, laneCount);
        return new AuthorityCommandLane(
            route.domain(),
            route.partitionKey(),
            laneCount,
            assignedLane,
            fingerprint,
            route.domain() + ":lane:" + assignedLane
        );
    }

    Map<String, Object> payload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("domain", domain);
        values.put("partitionKey", partitionKey);
        values.put("laneCount", laneCount);
        values.put("lane", lane);
        values.put("laneKeyFingerprint", laneKeyFingerprint);
        values.put("fencingScope", fencingScope);
        return Map.copyOf(values);
    }

    private static int lane(String fingerprint, int laneCount) {
        if (laneCount <= 0) {
            throw new IllegalArgumentException("laneCount must be positive");
        }
        long seed = Long.parseUnsignedLong(fingerprint.substring(0, 16), 16);
        return (int) Long.remainderUnsigned(seed, laneCount);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
