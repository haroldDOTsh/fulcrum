package sh.harold.fulcrum.api.data.impl.authority.events;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityLogTopology;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;

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
    AuthorityStateRestoreTarget,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PresenceReader,
    DataAuthority.PlayerRankReader {
    public static final String PROJECTION_NAME = "authority-hot-state";
    public static final String PROJECTION_VERSION = "authority-hot-state-v1";

    private static final Gson GSON = new Gson();
    private static final String PLAYER_PROFILE = "player_profile";
    private static final String PRESENCE = "presence";
    private static final String PLAYER_RANK = "player_rank";
    private static final String UNKNOWN = "unknown";
    private static final List<String> PROFILE_EVENT_TYPES = List.of(
        "RECORD_PLAYER_LOGIN",
        "RECORD_PLAYER_LOGOUT"
    );
    private static final List<String> SESSION_EVENT_TYPES = List.of(
        "END_SESSION",
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
    private final ConcurrentMap<UUID, DataAuthority.PlayerPresenceSnapshot> presences = new ConcurrentHashMap<>();
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
    public String projectionVersion() {
        return PROJECTION_VERSION;
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
            change.presenceSnapshot().ifPresent(snapshot -> presences.compute(
                snapshot.subjectId(),
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
    public CompletionStage<Optional<DataAuthority.PlayerPresenceSnapshot>> findPresence(DataAuthority.Subject subject) {
        Objects.requireNonNull(subject, "subject");
        return CompletableFuture.completedFuture(Optional.ofNullable(presences.get(subject.subjectId())));
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot>> quotePresence(
        DataAuthority.Subject subject,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(subject, "subject");
        return CompletableFuture.completedFuture(quotePresenceSnapshot(
            subject.subjectId(),
            DataAuthority.ReadRequirement.orEventual(requirement),
            Optional.ofNullable(presences.get(subject.subjectId())),
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

    public AuthorityStateRestoreResult restore(AuthorityStateRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.hasValidStateFingerprint()) {
            return AuthorityStateRestoreResult.skipped(
                PROJECTION_VERSION,
                record,
                "state fingerprint mismatch"
            );
        }
        return switch (record.aggregateType()) {
            case PLAYER_PROFILE -> restoreProfile(record);
            case PRESENCE -> restorePresence(record);
            case PLAYER_RANK -> restoreRank(record);
            default -> AuthorityStateRestoreResult.skipped(
                PROJECTION_VERSION,
                record,
                "unsupported aggregate type " + record.aggregateType()
            );
        };
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
        if (SESSION_EVENT_TYPES.contains(eventType)) {
            DataAuthority.PlayerPresenceSnapshot snapshot = projectPresence(event, eventType);
            return ProjectionChange.presence(snapshot, outputFingerprint(SESSION_EVENT_TYPES, snapshotPayload(snapshot)));
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
        requireProfileEvent(eventType);
        String username = boundedUsername(string(payload.get("username"), current == null ? UNKNOWN : current.username()));
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        long totalPlaytimeMs = current == null ? 0L : current.totalPlaytimeMs();
        Map<String, Object> profileData = mergeProfileData(current, payload);
        DataAuthority.SnapshotWatermark watermark = watermark(
            event,
            PLAYER_PROFILE,
            outputFingerprint(PROFILE_EVENT_TYPES, profilePayload(
                playerId,
                username,
                normalizedUsername,
                totalPlaytimeMs,
                profileData,
                event.revision()
            ))
        );
        return new DataAuthority.PlayerProfileSnapshot(
            playerId,
            username,
            normalizedUsername,
            false,
            null,
            null,
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

    private DataAuthority.PlayerPresenceSnapshot projectPresence(AuthorityEventEnvelope event, String eventType) {
        requireAggregateType(event, PRESENCE);
        UUID subjectId = subjectId(event);
        DataAuthority.PlayerPresenceSnapshot current = presences.get(subjectId);
        if (current != null && current.revision() > event.revision()) {
            return current;
        }

        Map<?, ?> payload = commandPayload(event);
        boolean online = !"END_SESSION".equals(eventType);
        UUID playerId = uuid(payload.get("playerId"));
        String username = boundedUsername(string(
            payload.get("username"),
            current == null ? UNKNOWN : current.username()
        ));
        UUID sessionId = uuid(payload.get("sessionId"));
        long observedAt = longValue(
            payload.get("observedAt"),
            longValue(payload.get("timestamp"), event.createdAt().toEpochMilli())
        );
        DataAuthority.SnapshotWatermark watermark = watermark(
            event,
            PRESENCE,
            outputFingerprint(SESSION_EVENT_TYPES, presencePayload(
                subjectId,
                playerId,
                username,
                online,
                online ? string(payload.get("currentServer"), null) : null,
                online ? string(payload.get("currentProxy"), null) : null,
                sessionId,
                observedAt,
                event.revision()
            ))
        );
        return new DataAuthority.PlayerPresenceSnapshot(
            subjectId,
            playerId,
            username,
            online,
            online ? string(payload.get("currentServer"), null) : null,
            online ? string(payload.get("currentProxy"), null) : null,
            sessionId,
            observedAt,
            event.revision(),
            watermark
        );
    }

    private AuthorityStateRestoreResult restoreProfile(AuthorityStateRecord record) {
        Map<String, Object> payload = record.statePayload();
        UUID playerId = uuid(payload.get("playerId"));
        if (playerId == null) {
            playerId = uuid(record.aggregateId());
        }
        if (playerId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing playerId");
        }
        DataAuthority.PlayerProfileSnapshot snapshot = new DataAuthority.PlayerProfileSnapshot(
            playerId,
            boundedUsername(string(payload.get("username"), UNKNOWN)),
            string(payload.get("normalizedUsername"), string(payload.get("username"), UNKNOWN).toLowerCase(Locale.ROOT)),
            false,
            null,
            null,
            longValue(payload.get("totalPlaytimeMs"), 0L),
            profileData(payload.get("profileData")),
            longValue(payload.get("revision"), record.revision()),
            watermark(record)
        );
        profiles.compute(playerId, (ignored, current) -> newerSnapshot(current, snapshot) ? snapshot : current);
        return AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record);
    }

    private AuthorityStateRestoreResult restoreRank(AuthorityStateRecord record) {
        Map<String, Object> payload = record.statePayload();
        UUID playerId = uuid(payload.get("playerId"));
        if (playerId == null) {
            playerId = uuid(record.aggregateId());
        }
        if (playerId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing playerId");
        }
        String primaryRank = string(payload.get("primaryRank"), "DEFAULT");
        List<String> restoredRanks = strings(payload.get("ranks"));
        if (restoredRanks.isEmpty()) {
            restoredRanks = List.of(primaryRank);
        }
        DataAuthority.PlayerRankSnapshot snapshot = new DataAuthority.PlayerRankSnapshot(
            playerId,
            primaryRank,
            restoredRanks,
            longValue(payload.get("revision"), record.revision()),
            watermark(record)
        );
        ranks.compute(playerId, (ignored, current) -> newerSnapshot(current, snapshot) ? snapshot : current);
        return AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record);
    }

    private AuthorityStateRestoreResult restorePresence(AuthorityStateRecord record) {
        Map<String, Object> payload = record.statePayload();
        UUID subjectId = uuid(payload.get("subjectId"));
        if (subjectId == null) {
            subjectId = uuid(record.aggregateId());
        }
        if (subjectId == null) {
            return AuthorityStateRestoreResult.skipped(PROJECTION_VERSION, record, "state record is missing subjectId");
        }
        DataAuthority.PlayerPresenceSnapshot snapshot = new DataAuthority.PlayerPresenceSnapshot(
            subjectId,
            uuid(payload.get("playerId")),
            boundedUsername(string(payload.get("username"), UNKNOWN)),
            booleanValue(payload.get("online")),
            string(payload.get("currentServer"), null),
            string(payload.get("currentProxy"), null),
            uuid(payload.get("sessionId")),
            longValue(payload.get("observedAt"), record.eventCreatedAt().toEpochMilli()),
            longValue(payload.get("revision"), record.revision()),
            watermark(record)
        );
        presences.compute(subjectId, (ignored, current) -> newerSnapshot(current, snapshot) ? snapshot : current);
        return AuthorityStateRestoreResult.restored(PROJECTION_VERSION, record);
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

    private DataAuthority.QuotedRead<DataAuthority.PlayerPresenceSnapshot> quotePresenceSnapshot(
        UUID subjectId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerPresenceSnapshot> snapshot,
        long nowEpochMillis
    ) {
        return quoteSnapshot(
            DataAuthority.Subject.SCOPE_PREFIX + subjectId,
            PRESENCE,
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
                DataAuthority.ReadProvenance.hotState(),
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
        } else if (!stateTopicMatches(projectionFamily, watermark.stateTopic())) {
            status = DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE;
        } else if (requirement.visibilityToken() != null && !watermark.satisfies(requirement.visibilityToken())) {
            status = DataAuthority.ReadQuoteStatus.VISIBILITY_TOKEN_MISMATCH;
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
            DataAuthority.ReadProvenance.hotState(),
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
        if (snapshot instanceof DataAuthority.PlayerPresenceSnapshot presence) {
            return new ProjectedSnapshot(presence.revision(), presence.watermark());
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
        String commandDomain = string(route.get("domain"), projectionFamily);
        String partitionKey = string(route.get("partitionKey"), event.aggregateScope());
        return new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            event.aggregateScope(),
            aggregateType,
            event.aggregateId(),
            commandDomain,
            string(route.get("stateTopic"), "state." + projectionFamily),
            partitionKey,
            event.commandId(),
            event.eventId(),
            event.revision(),
            event.createdAt().toEpochMilli(),
            AuthorityLogTopology.partition(commandDomain, partitionKey),
            Math.max(0L, event.revision() - 1L),
            stateFingerprint,
            AuthorityEventFingerprints.inputFingerprint(event)
        );
    }

    private DataAuthority.SnapshotWatermark watermark(AuthorityStateRecord record) {
        String partitionKey = record.partitionKey();
        return new DataAuthority.SnapshotWatermark(
            PROJECTION_NAME,
            record.aggregateScope(),
            record.aggregateType(),
            record.aggregateId(),
            record.commandDomain(),
            record.stateTopic(),
            partitionKey,
            record.commandId(),
            record.eventId(),
            record.revision(),
            record.eventCreatedAt().toEpochMilli(),
            record.sourcePartition(),
            record.sourceOffset(),
            record.stateFingerprint(),
            record.eventChainHash()
        );
    }

    private static Map<String, Object> profilePayload(
        UUID playerId,
        String username,
        String normalizedUsername,
        long totalPlaytimeMs,
        Map<String, Object> profileData,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("username", username);
        values.put("normalizedUsername", normalizedUsername);
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

    private static Map<String, Object> presencePayload(
        UUID subjectId,
        UUID playerId,
        String username,
        boolean online,
        String currentServer,
        String currentProxy,
        UUID sessionId,
        long observedAt,
        long revision
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("subjectId", subjectId.toString());
        values.put("playerId", playerId == null ? null : playerId.toString());
        values.put("username", username);
        values.put("online", online);
        values.put("currentServer", currentServer);
        values.put("currentProxy", currentProxy);
        values.put("sessionId", sessionId == null ? null : sessionId.toString());
        values.put("observedAt", observedAt);
        values.put("revision", revision);
        return values;
    }

    private static Map<String, Object> snapshotPayload(DataAuthority.PlayerProfileSnapshot snapshot) {
        return profilePayload(
            snapshot.playerId(),
            snapshot.username(),
            snapshot.normalizedUsername(),
            snapshot.totalPlaytimeMs(),
            snapshot.profileData(),
            snapshot.revision()
        );
    }

    private static Map<String, Object> snapshotPayload(DataAuthority.PlayerRankSnapshot snapshot) {
        return rankPayload(snapshot.playerId(), snapshot.primaryRank(), snapshot.ranks(), snapshot.revision());
    }

    private static Map<String, Object> snapshotPayload(DataAuthority.PlayerPresenceSnapshot snapshot) {
        return presencePayload(
            snapshot.subjectId(),
            snapshot.playerId(),
            snapshot.username(),
            snapshot.online(),
            snapshot.currentServer(),
            snapshot.currentProxy(),
            snapshot.sessionId(),
            snapshot.observedAtEpochMillis(),
            snapshot.revision()
        );
    }

    private static Map<String, Object> mergeProfileData(
        DataAuthority.PlayerProfileSnapshot current,
        Map<?, ?> payload
    ) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (current != null) {
            current.profileData().forEach((key, value) -> {
                if (!profilePresenceKey(key)) {
                    merged.put(key, value);
                }
            });
        }
        payload.forEach((key, value) -> {
            if (key != null && value != null && !profilePresenceKey(key.toString())) {
                merged.put(key.toString(), value);
            }
        });
        return Map.copyOf(merged);
    }

    private static void requireProfileEvent(String eventType) {
        if (!PROFILE_EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported profile event type " + eventType);
        }
    }

    private static boolean profilePresenceKey(String key) {
        return "online".equals(key) || "currentServer".equals(key) || "currentProxy".equals(key);
    }

    private static Map<String, Object> profileData(Object value) {
        Map<String, Object> raw = stringMap(value);
        if (raw.isEmpty()) {
            return raw;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        raw.forEach((key, field) -> {
            if (!profilePresenceKey(key)) {
                filtered.put(key, field);
            }
        });
        return Map.copyOf(filtered);
    }

    private static Map<?, ?> commandPayload(AuthorityEventEnvelope event) {
        Object payload = event.payload().get("payload");
        return payload instanceof Map<?, ?> map ? map : event.payload();
    }

    private static Map<?, ?> route(AuthorityEventEnvelope event) {
        Object route = event.payload().get("route");
        return route instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean stateTopicMatches(String projectionFamily, String stateTopic) {
        return DataAuthorityReadContracts.stateTopicMatches(projectionFamily, stateTopic);
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

    private static UUID subjectId(AuthorityEventEnvelope event) {
        UUID aggregateId = uuid(event.aggregateId());
        if (aggregateId != null) {
            return aggregateId;
        }
        UUID payloadSubjectId = uuid(commandPayload(event).get("subjectId"));
        if (payloadSubjectId != null) {
            return payloadSubjectId;
        }
        UUID payloadPlayerId = uuid(commandPayload(event).get("playerId"));
        if (payloadPlayerId != null) {
            return payloadPlayerId;
        }
        throw new IllegalArgumentException("Authority hot-state event is missing subjectId");
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

    private static boolean newerSnapshot(
        DataAuthority.PlayerPresenceSnapshot current,
        DataAuthority.PlayerPresenceSnapshot next
    ) {
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

    private static Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, child) -> {
            if (key != null && child != null) {
                values.put(key.toString(), child);
            }
        });
        return Map.copyOf(values);
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

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> acceptedEventTypes() {
        List<String> values = new ArrayList<>(PROFILE_EVENT_TYPES);
        values.addAll(SESSION_EVENT_TYPES);
        values.addAll(RANK_EVENT_TYPES);
        return Collections.unmodifiableList(values);
    }

    private record ProjectionChange(
        Optional<DataAuthority.PlayerProfileSnapshot> profileSnapshot,
        Optional<DataAuthority.PlayerPresenceSnapshot> presenceSnapshot,
        Optional<DataAuthority.PlayerRankSnapshot> rankSnapshot,
        String outputFingerprint
    ) {
        private static ProjectionChange profile(
            DataAuthority.PlayerProfileSnapshot snapshot,
            String outputFingerprint
        ) {
            return new ProjectionChange(Optional.of(snapshot), Optional.empty(), Optional.empty(), outputFingerprint);
        }

        private static ProjectionChange presence(
            DataAuthority.PlayerPresenceSnapshot snapshot,
            String outputFingerprint
        ) {
            return new ProjectionChange(Optional.empty(), Optional.of(snapshot), Optional.empty(), outputFingerprint);
        }

        private static ProjectionChange rank(
            DataAuthority.PlayerRankSnapshot snapshot,
            String outputFingerprint
        ) {
            return new ProjectionChange(Optional.empty(), Optional.empty(), Optional.of(snapshot), outputFingerprint);
        }

        private static ProjectionChange noop(String outputFingerprint) {
            return new ProjectionChange(Optional.empty(), Optional.empty(), Optional.empty(), outputFingerprint);
        }
    }

    private record ProjectedSnapshot(long revision, DataAuthority.SnapshotWatermark watermark) {
    }
}
