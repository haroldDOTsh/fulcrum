package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cassandra-shaped hot read projection backed by memory for substrate and replay contract tests.
 */
public final class InMemoryAuthorityHotStateProjection implements AuthorityEventDispatchTarget,
    AuthorityEventReplayTarget,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PlayerRankReader {
    public static final String PROJECTION_NAME = "authority-hot-state";
    public static final String PROJECTION_VERSION = "authority-hot-state-v1";

    private static final Gson GSON = new Gson();
    private static final String PLAYER_PROFILE = "player_profile";
    private static final String PLAYER_RANK = "player_rank";
    private static final String UNKNOWN = "unknown";
    private static final List<String> PROFILE_EVENT_TYPES = List.of(
        "END_SESSION",
        "RECORD_PLAYER_LOGIN",
        "RECORD_PLAYER_LOGOUT",
        "RENEW_SESSION",
        "START_SESSION"
    );
    private static final List<String> RANK_EVENT_TYPES = List.of("GRANT_RANK", "REVOKE_RANK");
    private static final AuthorityProjectionManifest MANIFEST = AuthorityProjectionManifest.of(
        PROJECTION_NAME,
        PROJECTION_VERSION,
        acceptedEventTypes()
    );

    private final ConcurrentMap<UUID, DataAuthority.PlayerProfileSnapshot> profiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, DataAuthority.PlayerRankSnapshot> ranks = new ConcurrentHashMap<>();

    @Override
    public String consumerName() {
        return PROJECTION_NAME;
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public AuthorityProjectionManifest projectionManifest() {
        return MANIFEST;
    }

    @Override
    public AuthorityEventDispatchResult dispatch(AuthorityEventEnvelope event) {
        try {
            ProjectionChange change = reduce(event);
            change.profileSnapshot().ifPresent(snapshot -> profiles.compute(
                snapshot.playerId(),
                (ignored, current) -> newerSnapshot(current, snapshot) ? snapshot : current
            ));
            change.rankSnapshot().ifPresent(snapshot -> ranks.compute(
                snapshot.playerId(),
                (ignored, current) -> newerSnapshot(current, snapshot) ? snapshot : current
            ));
            return AuthorityEventDispatchResult.success(PROJECTION_VERSION, change.outputFingerprint());
        } catch (IllegalArgumentException exception) {
            return AuthorityEventDispatchResult.quarantine(exception.getMessage());
        }
    }

    @Override
    public AuthorityEventReplayResult replay(AuthorityEventEnvelope event) {
        ProjectionChange change = reduce(event);
        return AuthorityEventReplayResult.success(PROJECTION_VERSION, change.outputFingerprint());
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(Optional.ofNullable(profiles.get(playerId)));
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(quoteProfileSnapshot(
            playerId,
            DataAuthority.ReadRequirement.orEventual(requirement),
            Optional.ofNullable(profiles.get(playerId)),
            System.currentTimeMillis()
        ));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(Optional.ofNullable(ranks.get(playerId)));
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.completedFuture(quoteRankSnapshot(
            playerId,
            DataAuthority.ReadRequirement.orEventual(requirement),
            Optional.ofNullable(ranks.get(playerId)),
            System.currentTimeMillis()
        ));
    }

    private ProjectionChange reduce(AuthorityEventEnvelope event) {
        Objects.requireNonNull(event, "event");
        String eventType = event.eventType().trim();
        if (PROFILE_EVENT_TYPES.contains(eventType)) {
            DataAuthority.PlayerProfileSnapshot snapshot = projectProfile(event, eventType);
            return ProjectionChange.profile(snapshot, outputFingerprint(PROFILE_EVENT_TYPES, snapshotPayload(snapshot)));
        }
        if (RANK_EVENT_TYPES.contains(eventType)) {
            DataAuthority.PlayerRankSnapshot snapshot = projectRank(event);
            return ProjectionChange.rank(snapshot, outputFingerprint(RANK_EVENT_TYPES, snapshotPayload(snapshot)));
        }
        return ProjectionChange.noop(outputFingerprint(List.of(eventType), Map.of("eventId", event.eventId().toString())));
    }

    private DataAuthority.PlayerProfileSnapshot projectProfile(AuthorityEventEnvelope event, String eventType) {
        requireAggregateType(event, PLAYER_PROFILE);
        UUID playerId = playerId(event);
        DataAuthority.PlayerProfileSnapshot current = profiles.get(playerId);
        if (current != null && current.revision() > event.revision()) {
            return current;
        }

        Map<?, ?> payload = commandPayload(event);
        boolean online = switch (eventType) {
            case "RECORD_PLAYER_LOGIN", "START_SESSION", "RENEW_SESSION" -> true;
            case "RECORD_PLAYER_LOGOUT", "END_SESSION" -> false;
            default -> throw new IllegalArgumentException("Unsupported profile event type " + eventType);
        };
        String username = boundedUsername(string(payload.get("username"), current == null ? UNKNOWN : current.username()));
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        String currentServer = online ? string(payload.get("currentServer"), null) : null;
        String currentProxy = online ? string(payload.get("currentProxy"), null) : null;
        long totalPlaytimeMs = current == null ? 0L : current.totalPlaytimeMs();
        Map<String, Object> profileData = mergeProfileData(current, payload);
        DataAuthority.SnapshotWatermark watermark = watermark(
            event,
            PLAYER_PROFILE,
            outputFingerprint(PROFILE_EVENT_TYPES, profilePayload(
                playerId,
                username,
                normalizedUsername,
                online,
                currentServer,
                currentProxy,
                totalPlaytimeMs,
                profileData,
                event.revision()
            ))
        );
        return new DataAuthority.PlayerProfileSnapshot(
            playerId,
            username,
            normalizedUsername,
            online,
            currentServer,
            currentProxy,
            totalPlaytimeMs,
            profileData,
            event.revision(),
            watermark
        );
    }

    private DataAuthority.PlayerRankSnapshot projectRank(AuthorityEventEnvelope event) {
        requireAggregateType(event, PLAYER_RANK);
        UUID playerId = playerId(event);
        DataAuthority.PlayerRankSnapshot current = ranks.get(playerId);
        if (current != null && current.revision() > event.revision()) {
            return current;
        }

        Map<?, ?> payload = commandPayload(event);
        String primaryRank = string(payload.get("primaryRank"), current == null ? "DEFAULT" : current.primaryRank());
        List<String> projectedRanks = strings(payload.get("ranks"));
        if (projectedRanks.isEmpty()) {
            projectedRanks = List.of(primaryRank);
        }
        DataAuthority.SnapshotWatermark watermark = watermark(
            event,
            PLAYER_RANK,
            outputFingerprint(RANK_EVENT_TYPES, rankPayload(playerId, primaryRank, projectedRanks, event.revision()))
        );
        return new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            projectedRanks,
            event.revision(),
            watermark
        );
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quoteProfileSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerProfileSnapshot> snapshot,
        long nowEpochMillis
    ) {
        return quoteSnapshot(
            "player:" + playerId,
            PLAYER_PROFILE,
            requirement,
            snapshot,
            nowEpochMillis
        );
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quoteRankSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerRankSnapshot> snapshot,
        long nowEpochMillis
    ) {
        return quoteSnapshot(
            "rank:player:" + playerId,
            PLAYER_RANK,
            requirement,
            snapshot,
            nowEpochMillis
        );
    }

    private <T> DataAuthority.QuotedRead<T> quoteSnapshot(
        String aggregateScope,
        String projectionFamily,
        DataAuthority.ReadRequirement requirement,
        Optional<T> snapshot,
        long nowEpochMillis
    ) {
        long requiredRevision = requirement.minimumRevision();
        if (snapshot.isEmpty()) {
            DataAuthority.ReadQuoteStatus status = requiredRevision > 0L
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
            return DataAuthority.QuotedRead.unsatisfied(new DataAuthority.ReadQuote(
                aggregateScope,
                projectionFamily,
                requiredRevision,
                0L,
                status,
                null,
                null,
                DataAuthority.ReadProvenance.cache(nowEpochMillis, nowEpochMillis, requirement.maxAgeMillis()),
                null
            ));
        }

        ProjectedSnapshot projected = projectedSnapshot(snapshot.get());
        DataAuthority.SnapshotWatermark watermark = projected.watermark();
        long observedRevision = Math.max(projected.revision(), watermark.sourceRevision());
        DataAuthority.ProjectionDeliveryReceipt receipt =
            DataAuthority.ProjectionDeliveryReceipt.fromWatermark(projectionFamily, watermark);
        DataAuthority.ReadQuoteStatus status;
        if (watermark == null || !watermark.watermarked()) {
            status = DataAuthority.ReadQuoteStatus.UNWATERMARKED;
        } else if (!aggregateScope.equals(watermark.aggregateScope())) {
            status = DataAuthority.ReadQuoteStatus.SCOPE_MISMATCH;
        } else if (!("state." + projectionFamily).equals(watermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (watermark.sourceRevision() != projected.revision()) {
            status = DataAuthority.ReadQuoteStatus.REVISION_MISMATCH;
        } else if (projected.revision() < requiredRevision || watermark.sourceRevision() < requiredRevision) {
            status = DataAuthority.ReadQuoteStatus.STALE_REVISION;
        } else if (receipt == null || !receipt.satisfies(projectionFamily, aggregateScope, requiredRevision)) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (requirement.hasMaxAge() && watermark.staleAt(nowEpochMillis, requirement.maxAgeMillis())) {
            status = DataAuthority.ReadQuoteStatus.EXPIRED;
        } else {
            status = DataAuthority.ReadQuoteStatus.SATISFIED;
        }

        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            aggregateScope,
            projectionFamily,
            requiredRevision,
            observedRevision,
            status,
            watermark,
            null,
            DataAuthority.ReadProvenance.cache(
                watermark.eventCreatedEpochMillis(),
                nowEpochMillis,
                requirement.maxAgeMillis()
            ),
            receipt
        );
        return status == DataAuthority.ReadQuoteStatus.SATISFIED
            ? DataAuthority.QuotedRead.satisfied(snapshot.get(), quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private static ProjectedSnapshot projectedSnapshot(Object snapshot) {
        if (snapshot instanceof DataAuthority.PlayerProfileSnapshot profile) {
            return new ProjectedSnapshot(profile.revision(), profile.watermark());
        }
        if (snapshot instanceof DataAuthority.PlayerRankSnapshot rank) {
            return new ProjectedSnapshot(rank.revision(), rank.watermark());
        }
        throw new IllegalArgumentException("Unsupported hot snapshot " + snapshot.getClass().getName());
    }

    private DataAuthority.SnapshotWatermark watermark(
        AuthorityEventEnvelope event,
        String projectionFamily,
        String stateFingerprint
    ) {
        Map<?, ?> route = route(event);
        String aggregateType = string(event.aggregateType(), projectionFamily);
        return new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            event.aggregateScope(),
            aggregateType,
            event.aggregateId(),
            string(route.get("domain"), projectionFamily),
            string(route.get("stateTopic"), "state." + projectionFamily),
            string(route.get("partitionKey"), event.aggregateScope()),
            event.commandId(),
            event.eventId(),
            event.revision(),
            event.createdAt().toEpochMilli(),
            stateFingerprint,
            AuthorityEventFingerprints.inputFingerprint(event)
        );
    }

    private static Map<String, Object> profilePayload(
        UUID playerId,
        String username,
        String normalizedUsername,
        boolean online,
        String currentServer,
        String currentProxy,
        long totalPlaytimeMs,
        Map<String, Object> profileData,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("username", username);
        values.put("normalizedUsername", normalizedUsername);
        values.put("online", online);
        values.put("currentServer", currentServer);
        values.put("currentProxy", currentProxy);
        values.put("totalPlaytimeMs", totalPlaytimeMs);
        values.put("profileData", profileData);
        values.put("revision", revision);
        return values;
    }

    private static Map<String, Object> rankPayload(
        UUID playerId,
        String primaryRank,
        List<String> ranks,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("primaryRank", primaryRank);
        values.put("ranks", ranks);
        values.put("revision", revision);
        return values;
    }

    private static Map<String, Object> snapshotPayload(DataAuthority.PlayerProfileSnapshot snapshot) {
        return profilePayload(
            snapshot.playerId(),
            snapshot.username(),
            snapshot.normalizedUsername(),
            snapshot.online(),
            snapshot.currentServer(),
            snapshot.currentProxy(),
            snapshot.totalPlaytimeMs(),
            snapshot.profileData(),
            snapshot.revision()
        );
    }

    private static Map<String, Object> snapshotPayload(DataAuthority.PlayerRankSnapshot snapshot) {
        return rankPayload(snapshot.playerId(), snapshot.primaryRank(), snapshot.ranks(), snapshot.revision());
    }

    private static Map<String, Object> mergeProfileData(
        DataAuthority.PlayerProfileSnapshot current,
        Map<?, ?> payload
    ) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current.profileData());
        }
        payload.forEach((key, value) -> {
            if (key != null && value != null) {
                merged.put(key.toString(), value);
            }
        });
        return Map.copyOf(merged);
    }

    private static Map<?, ?> commandPayload(AuthorityEventEnvelope event) {
        Object payload = event.payload().get("payload");
        return payload instanceof Map<?, ?> map ? map : event.payload();
    }

    private static Map<?, ?> route(AuthorityEventEnvelope event) {
        Object route = event.payload().get("route");
        return route instanceof Map<?, ?> map ? map : Map.of();
    }

    private static UUID playerId(AuthorityEventEnvelope event) {
        UUID aggregateId = uuid(event.aggregateId());
        if (aggregateId != null) {
            return aggregateId;
        }
        UUID payloadPlayerId = uuid(commandPayload(event).get("playerId"));
        if (payloadPlayerId != null) {
            return payloadPlayerId;
        }
        throw new IllegalArgumentException("Authority hot-state event is missing playerId");
    }

    private static void requireAggregateType(AuthorityEventEnvelope event, String expectedAggregateType) {
        if (!expectedAggregateType.equals(event.aggregateType())) {
            throw new IllegalArgumentException(
                "Authority hot-state event aggregate type mismatch: expected "
                    + expectedAggregateType + " but received " + event.aggregateType()
            );
        }
    }

    private static boolean newerSnapshot(DataAuthority.PlayerProfileSnapshot current, DataAuthority.PlayerProfileSnapshot next) {
        return current == null || next.revision() >= current.revision();
    }

    private static boolean newerSnapshot(DataAuthority.PlayerRankSnapshot current, DataAuthority.PlayerRankSnapshot next) {
        return current == null || next.revision() >= current.revision();
    }

    private static String outputFingerprint(Collection<String> reducers, Map<String, Object> snapshotPayload) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("projectionVersion", PROJECTION_VERSION);
        values.put("reducers", reducers);
        values.put("snapshot", snapshotPayload);
        return AuthorityEventFingerprints.sha256(GSON.toJson(canonicalValue(values)));
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> {
                if (key != null) {
                    sorted.put(key.toString(), canonicalValue(child));
                }
            });
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object child : iterable) {
                values.add(canonicalValue(child));
            }
            return values;
        }
        return value;
    }

    private static List<String> strings(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) {
                    values.add(item.toString());
                }
            }
            return values;
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return List.of(value.toString());
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String boundedUsername(String username) {
        String effectiveUsername = string(username, UNKNOWN);
        return effectiveUsername.length() > 16 ? effectiveUsername.substring(0, 16) : effectiveUsername;
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static List<String> acceptedEventTypes() {
        List<String> values = new ArrayList<>(PROFILE_EVENT_TYPES);
        values.addAll(RANK_EVENT_TYPES);
        return Collections.unmodifiableList(values);
    }

    private record ProjectionChange(
        Optional<DataAuthority.PlayerProfileSnapshot> profileSnapshot,
        Optional<DataAuthority.PlayerRankSnapshot> rankSnapshot,
        String outputFingerprint
    ) {
        private static ProjectionChange profile(
            DataAuthority.PlayerProfileSnapshot snapshot,
            String outputFingerprint
        ) {
            return new ProjectionChange(Optional.of(snapshot), Optional.empty(), outputFingerprint);
        }

        private static ProjectionChange rank(
            DataAuthority.PlayerRankSnapshot snapshot,
            String outputFingerprint
        ) {
            return new ProjectionChange(Optional.empty(), Optional.of(snapshot), outputFingerprint);
        }

        private static ProjectionChange noop(String outputFingerprint) {
            return new ProjectionChange(Optional.empty(), Optional.empty(), outputFingerprint);
        }
    }

    private record ProjectedSnapshot(long revision, DataAuthority.SnapshotWatermark watermark) {
    }
}
