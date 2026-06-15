package sh.harold.fulcrum.api.data.impl.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executable store-placement matrix for the target authority data layer.
 */
public final class AuthorityStorePlacements {
    public static final String PLAYER_PRESENCE =
        "Player presence (online, current proxy/server/slot, session id, last-seen)";
    public static final String LIVE_EFFECTIVE_RANKS = "Live effective ranks (for permission checks)";
    public static final String LIVE_MATCH_STATE = "Live match state (during a match)";
    public static final String PLAYER_PROFILE_OF_RECORD =
        "Player profile of record (identity, first_seen, total_playtime, slow attributes)";
    public static final String PLAYER_SETTINGS_COSMETICS = "Player settings / cosmetics (namespaced)";
    public static final String RANK_HISTORY_AUDIT = "Rank history + audit";
    public static final String PUNISHMENTS = "Punishments";
    public static final String SOCIAL_EDGES = "Social edges (friends/blocks)";
    public static final String SESSION_HISTORY = "Session history";
    public static final String MATCH_HISTORY_STATS = "Match history + participant stats";
    public static final String ANALYTICS_EVENTS = "Analytics events";
    public static final String COMMAND_AUDIT = "Command audit";
    public static final String IDEMPOTENCY_DEDUPE = "Idempotency dedupe";
    public static final String CAPACITY_LEASES_LOCKS = "Capacity leases / locks";
    public static final String SNAPSHOT_CACHE = "Snapshot cache";
    public static final String STATE_CHANGELOG = "Per-aggregate state changelog (restore source)";
    public static final String REGISTRY_STATE = "Node / slot registry state (data-layer hook)";

    private static final Map<String, StorePlacement> PLACEMENTS = placements();
    private static final String MATERIAL = material(PLACEMENTS);
    private static final String FINGERPRINT = sha256(MATERIAL);

    private AuthorityStorePlacements() {
    }

    public static Map<String, StorePlacement> all() {
        return PLACEMENTS;
    }

    public static StorePlacement placement(String concern) {
        StorePlacement placement = PLACEMENTS.get(concern);
        if (placement == null) {
            throw new IllegalArgumentException("No authority store placement for " + concern);
        }
        return placement;
    }

    public static String material() {
        return MATERIAL;
    }

    public static String fingerprint() {
        return FINGERPRINT;
    }

    private static Map<String, StorePlacement> placements() {
        Map<String, StorePlacement> values = new LinkedHashMap<>();
        put(values, new StorePlacement(
            PLAYER_PRESENCE,
            List.of("cassandra"),
            List.of(),
            "player_id",
            "authority-player",
            List.of("fleet-read-only", "services"),
            "Per-login route churn with TTL liveness."
        ));
        put(values, new StorePlacement(
            LIVE_EFFECTIVE_RANKS,
            List.of("cassandra"),
            List.of("valkey"),
            "player_id",
            "authority-rank",
            List.of("fleet-read-only"),
            "Permission checks need low-latency hot state."
        ));
        put(values, new StorePlacement(
            LIVE_MATCH_STATE,
            List.of("cassandra"),
            List.of(),
            "match_id",
            "authority-match",
            List.of("fleet-read-only", "services"),
            "Hot only while a match is running."
        ));
        put(values, new StorePlacement(
            PLAYER_PROFILE_OF_RECORD,
            List.of("postgresql"),
            List.of(),
            "player_id",
            "authority-player",
            List.of("postgresql-read-replica"),
            "Relational profile of record and slow attributes."
        ));
        put(values, new StorePlacement(
            PLAYER_SETTINGS_COSMETICS,
            List.of("postgresql"),
            List.of(),
            "player_id,namespace,key",
            "authority-player",
            List.of("postgresql-read-replica"),
            "Namespaced durable settings without a hot JSON blob."
        ));
        put(values, new StorePlacement(
            RANK_HISTORY_AUDIT,
            List.of("postgresql"),
            List.of(),
            "player_id,time",
            "authority-rank",
            List.of("postgresql-read-replica", "ops"),
            "Append-heavy audited rank history."
        ));
        put(values, new StorePlacement(
            PUNISHMENTS,
            List.of("postgresql"),
            List.of(),
            "player_id",
            "authority-moderation",
            List.of("postgresql-read-replica", "ops"),
            "Relational active punishment lookups and audit."
        ));
        put(values, new StorePlacement(
            SOCIAL_EDGES,
            List.of("postgresql"),
            List.of(),
            "owner,target,type",
            "authority-social",
            List.of("postgresql-read-replica"),
            "Graph-shaped relational lookups."
        ));
        put(values, new StorePlacement(
            SESSION_HISTORY,
            List.of("postgresql"),
            List.of(),
            "session_id,time",
            "authority-session",
            List.of("postgresql-read-replica", "ops"),
            "Append-heavy session audit; live state is separate."
        ));
        put(values, new StorePlacement(
            MATCH_HISTORY_STATS,
            List.of("postgresql"),
            List.of(),
            "match_id,time",
            "authority-match",
            List.of("postgresql-read-replica", "ops"),
            "Queryable match history and participant stats."
        ));
        put(values, new StorePlacement(
            ANALYTICS_EVENTS,
            List.of("kafka", "postgresql", "warehouse"),
            List.of(),
            "event_id,time",
            "sink-workers",
            List.of("analysts", "ops"),
            "The event log fronts high-volume analytics sinks."
        ));
        put(values, new StorePlacement(
            COMMAND_AUDIT,
            List.of("kafka", "postgresql"),
            List.of(),
            "command_id,time",
            "authority",
            List.of("ops"),
            "Durable command audit plus replayable command log."
        ));
        put(values, new StorePlacement(
            IDEMPOTENCY_DEDUPE,
            List.of("valkey", "postgresql"),
            List.of(),
            "idempotency_key",
            "authority",
            List.of("authority"),
            "Fast TTL dedupe with a durable unique backstop."
        ));
        put(values, new StorePlacement(
            CAPACITY_LEASES_LOCKS,
            List.of("valkey"),
            List.of(),
            "lease_keys",
            "authority-control-plane",
            List.of("control-plane"),
            "Ephemeral TTL coordination."
        ));
        put(values, new StorePlacement(
            SNAPSHOT_CACHE,
            List.of("valkey"),
            List.of(),
            "aggregate_id",
            "authority",
            List.of("fleet", "services"),
            "Nearline read offload and invalidation target."
        ));
        put(values, new StorePlacement(
            STATE_CHANGELOG,
            List.of("kafka"),
            List.of(),
            "aggregate_id",
            "authority",
            List.of("recovery", "projection-workers"),
            "Compacted restore source retained by key."
        ));
        put(values, new StorePlacement(
            REGISTRY_STATE,
            List.of("kafka", "postgresql"),
            List.of(),
            "node_id,slot_id",
            "control-plane",
            List.of("control-plane-on-boot"),
            "Registry snapshots must be replayable and read on startup."
        ));
        return Map.copyOf(values);
    }

    private static void put(Map<String, StorePlacement> values, StorePlacement placement) {
        values.put(placement.concern(), placement);
    }

    private static String material(Map<String, StorePlacement> placements) {
        StringBuilder material = new StringBuilder("authorityStorePlacements=v1\n");
        placements.values().stream()
            .sorted(Comparator.comparing(StorePlacement::concern))
            .forEach(placement -> material.append(placement.material()).append('\n'));
        return material.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority store placements", exception);
        }
    }

    private static List<String> sorted(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(AuthorityStorePlacements::requireText)
            .sorted()
            .toList();
    }

    private static String join(List<String> values) {
        return String.join(",", values);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        return value.trim();
    }

    public record StorePlacement(
        String concern,
        List<String> authoritativeStores,
        List<String> derivedStores,
        String partitionKey,
        String writtenBy,
        List<String> readBy,
        String reason
    ) {
        public StorePlacement {
            concern = requireText(concern);
            authoritativeStores = sorted(authoritativeStores);
            derivedStores = sorted(derivedStores);
            partitionKey = requireText(partitionKey);
            writtenBy = requireText(writtenBy);
            readBy = sorted(readBy);
            reason = requireText(reason);
            if (authoritativeStores.isEmpty()) {
                throw new IllegalArgumentException(concern + " requires at least one authoritative store");
            }
            Objects.requireNonNull(derivedStores, "derivedStores");
            if (readBy.isEmpty()) {
                throw new IllegalArgumentException(concern + " requires at least one reader");
            }
        }

        public List<String> allStores() {
            return java.util.stream.Stream.concat(authoritativeStores.stream(), derivedStores.stream())
                .distinct()
                .sorted()
                .toList();
        }

        public String material() {
            return "placement|" + concern
                + "|authoritativeStores=" + join(authoritativeStores)
                + "|derivedStores=" + join(derivedStores)
                + "|partitionKey=" + partitionKey
                + "|writtenBy=" + writtenBy
                + "|readBy=" + join(readBy)
                + "|reason=" + reason;
        }
    }
}
