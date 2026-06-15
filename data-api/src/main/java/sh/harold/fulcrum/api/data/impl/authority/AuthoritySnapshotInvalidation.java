package sh.harold.fulcrum.api.data.impl.authority;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Advisory cache-coherence packet for authority snapshots.
 *
 * <p>This intentionally carries only snapshot identity and optional watermark evidence.
 * Profile and rank state still come from the authority read path. Packets without
 * watermarks are revision-floor pulses only: they can evict stale cache lines and
 * force retryable quoted reads, but they never make a snapshot serveable.</p>
 */
public record AuthoritySnapshotInvalidation(
    int schemaVersion,
    String projectionFamily,
    String aggregateScope,
    String aggregateType,
    String aggregateId,
    long revision,
    DataAuthority.SnapshotWatermark watermark
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String PLAYER_PROFILE = "player_profile";
    public static final String PLAYER_PRESENCE = "presence";
    public static final String PLAYER_RANK = "player_rank";

    private static final Gson GSON = new Gson();

    public AuthoritySnapshotInvalidation {
        projectionFamily = normalize(projectionFamily);
        aggregateScope = normalize(aggregateScope);
        aggregateType = normalize(aggregateType);
        aggregateId = normalize(aggregateId);
        revision = Math.max(0L, revision);
    }

    public static Optional<AuthoritySnapshotInvalidation> fromProfileSnapshot(
        DataAuthority.PlayerProfileSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return fromSnapshot(PLAYER_PROFILE, snapshot.watermark());
    }

    public static Optional<AuthoritySnapshotInvalidation> fromRankSnapshot(
        DataAuthority.PlayerRankSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return fromSnapshot(PLAYER_RANK, snapshot.watermark());
    }

    public static Optional<AuthoritySnapshotInvalidation> fromPresenceSnapshot(
        DataAuthority.PlayerPresenceSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return fromSnapshot(PLAYER_PRESENCE, snapshot.watermark());
    }

    public static Optional<AuthoritySnapshotInvalidation> revisionFloorFor(
        DataAuthority.AuthorityCommand command,
        long revision
    ) {
        Objects.requireNonNull(command, "command");
        if (revision <= 0L) {
            return Optional.empty();
        }
        try {
            AuthorityDomainDeclarations.CommandDeclaration declaration = AuthorityDomainDeclarations.command(
                command.declarationId()
            );
            Map<String, Object> payload = AuthorityCommandPayloads.payload(command);
            Object aggregateId = payload.get(declaration.aggregateIdField());
            if (aggregateId == null || aggregateId.toString().isBlank()) {
                return Optional.empty();
            }
            String aggregateIdValue = aggregateId.toString();
            return revisionFloor(
                declaration.projectionFamily(),
                declaration.aggregateScopePrefix() + aggregateIdValue,
                declaration.projectionFamily(),
                aggregateIdValue,
                revision
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<AuthoritySnapshotInvalidation> fromPayload(Map<?, ?> payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            DataAuthority.SnapshotWatermark watermark = DataAuthority.SnapshotWatermark.fromPayload(
                mapValue(payload.get("watermark")),
                null
            );
            AuthoritySnapshotInvalidation invalidation = new AuthoritySnapshotInvalidation(
                intValue(payload.get("schemaVersion"), 0),
                string(payload.get("projectionFamily")),
                string(payload.get("aggregateScope")),
                string(payload.get("aggregateType")),
                string(payload.get("aggregateId")),
                longValue(payload.get("revision"), 0L),
                watermark
            );
            return invalidation.actionable() ? Optional.of(invalidation) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static Optional<AuthoritySnapshotInvalidation> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return fromPayload(GSON.fromJson(json, Map.class));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public boolean actionable() {
        return schemaVersion == SCHEMA_VERSION
            && knownProjectionFamily()
            && revision > 0L
            && aggregateType.equals(projectionFamily)
            && expectedScope(projectionFamily, aggregateId).equals(aggregateScope)
            && (
                watermark == null
                    || (watermark.watermarked()
                    && revision == watermark.sourceRevision()
                    && aggregateScope.equals(watermark.aggregateScope())
                    && aggregateType.equals(watermark.aggregateType())
                    && aggregateId.equals(watermark.aggregateId())
                    && projectionFamily.equals(watermark.aggregateType())
                    && DataAuthorityReadContracts.stateTopicMatches(projectionFamily, watermark.stateTopic())
                    && aggregateScope.equals(watermark.partitionKey()))
            );
    }

    public Map<String, Object> payload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", schemaVersion);
        values.put("projectionFamily", projectionFamily);
        values.put("aggregateScope", aggregateScope);
        values.put("aggregateType", aggregateType);
        values.put("aggregateId", aggregateId);
        values.put("revision", revision);
        values.put("watermark", watermark == null ? Map.of() : watermark.payload());
        return Map.copyOf(values);
    }

    private boolean knownProjectionFamily() {
        return PLAYER_PROFILE.equals(projectionFamily)
            || PLAYER_PRESENCE.equals(projectionFamily)
            || PLAYER_RANK.equals(projectionFamily);
    }

    private static Optional<AuthoritySnapshotInvalidation> fromSnapshot(
        String projectionFamily,
        DataAuthority.SnapshotWatermark watermark
    ) {
        AuthoritySnapshotInvalidation invalidation = new AuthoritySnapshotInvalidation(
            SCHEMA_VERSION,
            projectionFamily,
            watermark == null ? null : watermark.aggregateScope(),
            watermark == null ? null : watermark.aggregateType(),
            watermark == null ? null : watermark.aggregateId(),
            watermark == null ? 0L : watermark.sourceRevision(),
            watermark
        );
        return invalidation.actionable() ? Optional.of(invalidation) : Optional.empty();
    }

    private static Optional<AuthoritySnapshotInvalidation> revisionFloor(
        String projectionFamily,
        String aggregateScope,
        String aggregateType,
        String aggregateId,
        long revision
    ) {
        AuthoritySnapshotInvalidation invalidation = new AuthoritySnapshotInvalidation(
            SCHEMA_VERSION,
            projectionFamily,
            aggregateScope,
            aggregateType,
            aggregateId,
            revision,
            null
        );
        return invalidation.actionable() ? Optional.of(invalidation) : Optional.empty();
    }

    private static String expectedScope(String projectionFamily, String aggregateId) {
        if (PLAYER_PROFILE.equals(projectionFamily)) {
            return "player:" + aggregateId;
        }
        if (PLAYER_RANK.equals(projectionFamily)) {
            return "rank:player:" + aggregateId;
        }
        if (PLAYER_PRESENCE.equals(projectionFamily)) {
            return DataAuthority.Subject.SCOPE_PREFIX + aggregateId;
        }
        return "";
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || value.toString().isBlank() ? fallback : Long.parseLong(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value;
    }
}
