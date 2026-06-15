package sh.harold.fulcrum.api.data.impl.messagebus;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityTopologyEvidence;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.MessageBus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class MessageBusDataAuthorityClient implements DataAuthority.CommandPort,
    DataAuthority.PlayerProfileReader,
    DataAuthority.PlayerRankReader {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final MessageBus messageBus;
    private final String authorityServerId;
    private final Duration timeout;

    public MessageBusDataAuthorityClient(MessageBus messageBus, String authorityServerId) {
        this(messageBus, authorityServerId, DEFAULT_TIMEOUT);
    }

    public MessageBusDataAuthorityClient(MessageBus messageBus, String authorityServerId, Duration timeout) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        if (authorityServerId == null || authorityServerId.isBlank()) {
            throw new IllegalArgumentException("authorityServerId is required");
        }
        this.authorityServerId = authorityServerId;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public CompletionStage<DataAuthority.CommandResult> submit(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        Map<String, Object> route = DataAuthorityCommandContracts.routePayload(command);
        return messageBus.request(authorityServerId, commandTopic(route), commandPayload(command, route), timeout)
            .thenApply(response -> commandResult(command, response));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerProfileSnapshot>> findProfile(UUID playerId) {
        return quoteProfile(playerId, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> read.satisfied() ? read.snapshot() : Optional.empty());
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot>> quoteProfile(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            requirement
        );
        return messageBus.request(authorityServerId, MessageBusAuthorityChannels.PROFILE_READ,
                profileReadPayload(playerId, effectiveRequirement), timeout)
            .thenApply(response -> quotedProfileSnapshot(playerId, effectiveRequirement, response));
    }

    @Override
    public CompletionStage<Optional<DataAuthority.PlayerRankSnapshot>> findRanks(UUID playerId) {
        return quoteRanks(playerId, DataAuthority.ReadRequirement.eventual())
            .thenApply(read -> read.satisfied() ? read.snapshot() : Optional.empty());
    }

    @Override
    public CompletionStage<DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot>> quoteRanks(
        UUID playerId,
        DataAuthority.ReadRequirement requirement
    ) {
        Objects.requireNonNull(playerId, "playerId");
        DataAuthority.ReadRequirement effectiveRequirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            requirement
        );
        return messageBus.request(
                authorityServerId,
                MessageBusAuthorityChannels.RANK_READ,
                rankReadPayload(playerId, effectiveRequirement),
                timeout
            )
            .thenApply(response -> quotedRankSnapshot(playerId, effectiveRequirement, response));
    }

    private Map<String, Object> commandPayload(DataAuthority.AuthorityCommand command, Map<String, Object> route) {
        DataAuthority.CommandManifest manifest = command.manifest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("commandId", manifest.commandId().toString());
        values.put("commandType", manifest.type().name());
        values.put("actorId", manifest.actorId());
        values.put("scope", manifest.scope());
        values.put("idempotencyKey", manifest.idempotencyKey());
        values.put("deadlineEpochMillis", manifest.deadlineEpochMillis());
        values.put("fencingToken", manifest.fencingToken());
        values.put("expectedRevision", manifest.expectedRevision());
        values.put("schemaVersion", manifest.schemaVersion());
        values.put("contractFingerprint", DataAuthorityCommandContracts.fingerprint());
        values.put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        values.putAll(AuthorityTopologyEvidence.commandWirePayload(manifest.type(), manifest.scope(), route));
        values.put("route", route);
        values.put("provenance", clientProvenance(manifest.provenance()).payload());
        values.put("payload", command.payload());
        return Map.copyOf(values);
    }

    private static String commandTopic(Map<String, Object> route) {
        Object commandTopic = route.get("commandTopic");
        if (commandTopic == null || commandTopic.toString().isBlank()) {
            throw new IllegalArgumentException("Authority command route is missing commandTopic");
        }
        return commandTopic.toString();
    }

    private DataAuthority.CommandProvenance clientProvenance(DataAuthority.CommandProvenance submitted) {
        String currentServerId = messageBus.currentServerId();
        String originNode = known(submitted.originNode()) ? submitted.originNode() : currentServerId;
        if (!known(originNode)) {
            originNode = "unknown";
        }
        return new DataAuthority.CommandProvenance(
            originNode,
            "messagebus:" + originNode + "->" + authorityServerId,
            "message-bus-client",
            submitted.contractVersion()
        );
    }

    private Map<String, Object> profileReadPayload(UUID playerId, DataAuthority.ReadRequirement requirement) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("minimumRevision", requirement.minimumRevision());
        values.put("maxAgeMillis", requirement.maxAgeMillis());
        if (requirement.visibilityToken() != null) {
            values.put("visibilityToken", requirement.visibilityToken().payload());
        }
        return DataAuthorityReadContracts.payload(DataAuthorityReadContracts.ReadType.PLAYER_PROFILE, values);
    }

    private Map<String, Object> rankReadPayload(UUID playerId, DataAuthority.ReadRequirement requirement) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", playerId.toString());
        values.put("minimumRevision", requirement.minimumRevision());
        values.put("maxAgeMillis", requirement.maxAgeMillis());
        if (requirement.visibilityToken() != null) {
            values.put("visibilityToken", requirement.visibilityToken().payload());
        }
        return DataAuthorityReadContracts.payload(DataAuthorityReadContracts.ReadType.PLAYER_RANK, values);
    }

    private DataAuthority.CommandResult commandResult(DataAuthority.AuthorityCommand expectedCommand, Object raw) {
        Map<?, ?> response = asMap(raw);
        UUID commandId = uuid(response.get("commandId"));
        UUID expectedCommandId = expectedCommand.commandId();
        if (!expectedCommandId.equals(commandId)) {
            throw new IllegalStateException(
                "Authority command response commandId mismatch: expected "
                    + expectedCommandId + " but received " + commandId
            );
        }
        long revision = longValue(response.get("revision"), 0L);
        DataAuthority.CommandSettlement settlement = DataAuthority.CommandSettlement.fromPayload(
            mapValue(response.get("settlement")),
            DataAuthority.CommandSettlement.unsettled(revision)
        );
        DataAuthority.CommandRefusalReceipt refusalReceipt = DataAuthority.CommandRefusalReceipt.fromPayload(
            mapValue(response.get("refusalReceipt")),
            null
        );
        DataAuthority.CommandResult result = new DataAuthority.CommandResult(
            commandId,
            booleanValue(response.get("accepted"), false),
            revision,
            rejectionReason(response.get("rejectionReason")),
            string(response.get("message")),
            settlement,
            refusalReceipt
        );
        DataAuthorityCommandContracts.validateResult(expectedCommand, result);
        return result;
    }

    private Optional<DataAuthority.PlayerProfileSnapshot> profileSnapshot(UUID expectedPlayerId, Object raw) {
        Map<?, ?> response = asMap(raw);
        if (!booleanValue(response.get("found"), false)) {
            return Optional.empty();
        }
        Map<?, ?> profileData = mapValue(response.get("profileData"));
        UUID playerId = uuid(response.get("playerId"));
        if (!expectedPlayerId.equals(playerId)) {
            throw new IllegalStateException(
                "Authority profile response playerId mismatch: expected "
                    + expectedPlayerId + " but received " + playerId
            );
        }
        long revision = longValue(response.get("revision"), 0L);
        return Optional.of(new DataAuthority.PlayerProfileSnapshot(
            playerId,
            string(response.get("username")),
            string(response.get("normalizedUsername")),
            booleanValue(response.get("online"), false),
            string(response.get("currentServer")),
            string(response.get("currentProxy")),
            longValue(response.get("totalPlaytimeMs"), 0L),
            stringObjectMap(profileData),
            revision,
            DataAuthority.SnapshotWatermark.fromPayload(
                mapValue(response.get("watermark")),
                DataAuthority.SnapshotWatermark.unwatermarked(
                    "player:" + playerId,
                    "player_profile",
                    playerId.toString(),
                    revision
                )
            )
        ));
    }

    private Optional<DataAuthority.PlayerRankSnapshot> rankSnapshot(UUID expectedPlayerId, Object raw) {
        Map<?, ?> response = asMap(raw);
        if (!booleanValue(response.get("found"), false)) {
            return Optional.empty();
        }
        UUID playerId = uuid(response.get("playerId"));
        if (!expectedPlayerId.equals(playerId)) {
            throw new IllegalStateException(
                "Authority rank response playerId mismatch: expected "
                    + expectedPlayerId + " but received " + playerId
            );
        }
        long revision = longValue(response.get("revision"), 0L);
        return Optional.of(new DataAuthority.PlayerRankSnapshot(
            playerId,
            string(response.get("primaryRank")),
            stringList(response.get("ranks")),
            revision,
            DataAuthority.SnapshotWatermark.fromPayload(
                mapValue(response.get("watermark")),
                DataAuthority.SnapshotWatermark.unwatermarked(
                    "rank:player:" + playerId,
                    "player_rank",
                    playerId.toString(),
                    revision
                )
            )
        ));
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> quotedRankSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Object raw
    ) {
        Map<?, ?> response = asMap(raw);
        String expectedScope = "rank:player:" + playerId;
        validateReadQuote(response, expectedScope, "player_rank");
        Optional<DataAuthority.PlayerRankSnapshot> snapshot = rankSnapshot(playerId, response);
        DataAuthority.ReadQuote fallback = fallbackRankQuote(playerId, requirement, snapshot);
        DataAuthority.ReadQuote quote = DataAuthority.ReadQuote.fromPayload(
            mapValue(response.get("quote")),
            fallback
        );
        DataAuthorityReadContracts.validateQuote(quote, expectedScope, "player_rank", requirement);
        return quote.satisfied()
            ? DataAuthority.QuotedRead.satisfied(snapshot.orElseThrow(), quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> quotedProfileSnapshot(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Object raw
    ) {
        Map<?, ?> response = asMap(raw);
        String expectedScope = "player:" + playerId;
        validateReadQuote(response, expectedScope, "player_profile");
        Optional<DataAuthority.PlayerProfileSnapshot> snapshot = profileSnapshot(playerId, response);
        DataAuthority.ReadQuote fallback = fallbackProfileQuote(playerId, requirement, snapshot);
        DataAuthority.ReadQuote quote = DataAuthority.ReadQuote.fromPayload(
            mapValue(response.get("quote")),
            fallback
        );
        DataAuthorityReadContracts.validateQuote(quote, expectedScope, "player_profile", requirement);
        return quote.satisfied()
            ? DataAuthority.QuotedRead.satisfied(snapshot.orElseThrow(), quote)
            : DataAuthority.QuotedRead.unsatisfied(quote);
    }

    private void validateReadQuote(Map<?, ?> response, String expectedScope, String expectedProjectionFamily) {
        Map<?, ?> quote = mapValue(response.get("quote"));
        if (quote.isEmpty()) {
            return;
        }
        String aggregateScope = string(quote.get("aggregateScope"));
        if (aggregateScope != null && !expectedScope.equals(aggregateScope)) {
            throw new IllegalStateException(
                "Authority read quote aggregateScope mismatch: expected "
                    + expectedScope + " but received " + aggregateScope
            );
        }
        String projectionFamily = string(quote.get("projectionFamily"));
        if (projectionFamily != null && !expectedProjectionFamily.equals(projectionFamily)) {
            throw new IllegalStateException(
                "Authority read quote projectionFamily mismatch: expected "
                    + expectedProjectionFamily + " but received " + projectionFamily
            );
        }
    }

    private DataAuthority.ReadQuote fallbackRankQuote(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerRankSnapshot> snapshot
    ) {
        String aggregateScope = "rank:player:" + playerId;
        long requiredRevision = DataAuthority.ReadRequirement.orEventual(requirement).minimumRevision();
        long observedRevision = snapshot.map(DataAuthority.PlayerRankSnapshot::revision).orElse(0L);
        DataAuthority.ReadQuoteStatus status = snapshot.isPresent()
            ? DataAuthority.ReadQuoteStatus.SATISFIED
            : requiredRevision > 0L
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
        return new DataAuthority.ReadQuote(
            aggregateScope,
            "player_rank",
            requiredRevision,
            observedRevision,
            status,
            snapshot.map(DataAuthority.PlayerRankSnapshot::watermark).orElse(null),
            null,
            DataAuthority.ReadProvenance.authority()
        );
    }

    private DataAuthority.ReadQuote fallbackProfileQuote(
        UUID playerId,
        DataAuthority.ReadRequirement requirement,
        Optional<DataAuthority.PlayerProfileSnapshot> snapshot
    ) {
        String aggregateScope = "player:" + playerId;
        long requiredRevision = DataAuthority.ReadRequirement.orEventual(requirement).minimumRevision();
        long observedRevision = snapshot.map(DataAuthority.PlayerProfileSnapshot::revision).orElse(0L);
        DataAuthority.ReadQuoteStatus status = snapshot.isPresent()
            ? DataAuthority.ReadQuoteStatus.SATISFIED
            : requiredRevision > 0L
                ? DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE
                : DataAuthority.ReadQuoteStatus.NOT_FOUND;
        return new DataAuthority.ReadQuote(
            aggregateScope,
            "player_profile",
            requiredRevision,
            observedRevision,
            status,
            snapshot.map(DataAuthority.PlayerProfileSnapshot::watermark).orElse(null),
            null,
            DataAuthority.ReadProvenance.authority()
        );
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("Expected map response from authority transport");
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                values.put(key.toString(), value);
            }
        });
        return values;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("UUID value is required");
        }
        return UUID.fromString(value.toString());
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }

    private static DataAuthority.RejectionReason rejectionReason(Object value) {
        if (value == null || value.toString().isBlank()) {
            return DataAuthority.RejectionReason.NONE;
        }
        return DataAuthority.RejectionReason.valueOf(value.toString());
    }
}
