package sh.harold.fulcrum.api.data.impl.messagebus;

import com.google.gson.Gson;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityPrincipals;
import sh.harold.fulcrum.api.data.impl.authority.AuthoritySnapshotInvalidation;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityTopologyEvidence;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.RequestHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class MessageBusDataAuthorityProvider implements AutoCloseable {
    private final MessageBus messageBus;
    private final DataAuthority.CommandPort commandPort;
    private final CommandRefusalRecorder commandRefusalRecorder;
    private final DataAuthority.PlayerProfileReader profileReader;
    private final DataAuthority.PlayerRankReader rankReader;
    private final Supplier<DataAuthority.AuthorityBootIdentity> authorityBootIdentitySupplier;
    private final Gson gson = new Gson();

    private final RequestHandler commandHandler = this::handleCommand;
    private final RequestHandler profileReadHandler = this::handleProfileRead;
    private final RequestHandler rankReadHandler = this::handleRankRead;

    public MessageBusDataAuthorityProvider(
        MessageBus messageBus,
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader
    ) {
        this(messageBus, commandPort, profileReader, rankReader, ignored -> {
        });
    }

    public MessageBusDataAuthorityProvider(
        MessageBus messageBus,
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        CommandRefusalRecorder commandRefusalRecorder
    ) {
        this(
            messageBus,
            commandPort,
            profileReader,
            rankReader,
            DataAuthority.AuthorityBootIdentity.unknown(),
            commandRefusalRecorder
        );
    }

    public MessageBusDataAuthorityProvider(
        MessageBus messageBus,
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        DataAuthority.AuthorityBootIdentity authorityBootIdentity,
        CommandRefusalRecorder commandRefusalRecorder
    ) {
        this(
            messageBus,
            commandPort,
            profileReader,
            rankReader,
            () -> authorityBootIdentity,
            commandRefusalRecorder
        );
    }

    public MessageBusDataAuthorityProvider(
        MessageBus messageBus,
        DataAuthority.CommandPort commandPort,
        DataAuthority.PlayerProfileReader profileReader,
        DataAuthority.PlayerRankReader rankReader,
        Supplier<DataAuthority.AuthorityBootIdentity> authorityBootIdentitySupplier,
        CommandRefusalRecorder commandRefusalRecorder
    ) {
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.profileReader = Objects.requireNonNull(profileReader, "profileReader");
        this.rankReader = Objects.requireNonNull(rankReader, "rankReader");
        this.authorityBootIdentitySupplier = authorityBootIdentitySupplier == null
            ? DataAuthority.AuthorityBootIdentity::unknown
            : authorityBootIdentitySupplier;
        this.commandRefusalRecorder = Objects.requireNonNull(commandRefusalRecorder, "commandRefusalRecorder");
    }

    public void start() {
        DataAuthorityCommandContracts.commandTopics()
            .forEach(topic -> messageBus.subscribeRequest(topic, commandHandler));
        messageBus.subscribeRequest(MessageBusAuthorityChannels.PROFILE_READ, profileReadHandler);
        messageBus.subscribeRequest(MessageBusAuthorityChannels.RANK_READ, rankReadHandler);
    }

    @Override
    public void close() {
        DataAuthorityCommandContracts.commandTopics()
            .forEach(topic -> messageBus.unsubscribeRequest(topic, commandHandler));
        messageBus.unsubscribeRequest(MessageBusAuthorityChannels.PROFILE_READ, profileReadHandler);
        messageBus.unsubscribeRequest(MessageBusAuthorityChannels.RANK_READ, rankReadHandler);
    }

    private CompletionStage<Object> handleCommand(MessageEnvelope envelope) {
        Map<String, Object> wire = mapPayload(envelope);
        DataAuthority.CommandResult contractRejection = contractRejection(wire);
        if (contractRejection != null) {
            DataAuthority.CommandResult recorded = recordCommandRefusal(envelope, wire, contractRejection);
            return CompletableFuture.completedFuture(commandResponse(withCommandRefusalReceipt(
                envelope,
                wire,
                recorded
            )));
        }
        DataAuthority.AuthorityCommand command;
        try {
            command = command(wire, envelope);
        } catch (IllegalArgumentException exception) {
            DataAuthority.CommandResult recorded = recordCommandRefusal(envelope, wire, commandRejection(wire, exception));
            return CompletableFuture.completedFuture(commandResponse(withCommandRefusalReceipt(
                envelope,
                wire,
                recorded
            )));
        }
        return commandPort.submit(command).thenApply(result -> {
            if (result.accepted()) {
                publishSnapshotInvalidation(command, result.revision());
                return commandResponse(result);
            }
            DataAuthority.CommandResult responseResult = shouldRecordPrincipalRefusal(command, result)
                ? recordCommandRefusal(envelope, wire, result)
                : result;
            return commandResponse(withCommandRefusalReceipt(envelope, wire, responseResult));
        });
    }

    private CompletionStage<Object> handleProfileRead(MessageEnvelope envelope) {
        Map<String, Object> payload = mapPayload(envelope);
        String rejection = DataAuthorityReadContracts.rejection(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            payload
        );
        if (rejection != null) {
            return CompletableFuture.completedFuture(profileReadRejectionResponse(payload, rejection));
        }
        UUID playerId = uuid(payload.get("playerId"));
        DataAuthority.ReadRequirement requirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            new DataAuthority.ReadRequirement(
                longValue(payload.get("minimumRevision"), 0L),
                longValue(payload.get("maxAgeMillis"), -1L),
                DataAuthority.ReadVisibilityToken.fromPayload(mapValue(payload.get("visibilityToken")), null)
            )
        );
        return profileReader.quoteProfile(playerId, requirement).thenApply(this::profileResponse);
    }

    private CompletionStage<Object> handleRankRead(MessageEnvelope envelope) {
        Map<String, Object> payload = mapPayload(envelope);
        String rejection = DataAuthorityReadContracts.rejection(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            payload
        );
        if (rejection != null) {
            return CompletableFuture.completedFuture(rankReadRejectionResponse(payload, rejection));
        }
        UUID playerId = uuid(payload.get("playerId"));
        DataAuthority.ReadRequirement requirement = DataAuthorityReadContracts.effectiveRequirement(
            DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            new DataAuthority.ReadRequirement(
                longValue(payload.get("minimumRevision"), 0L),
                longValue(payload.get("maxAgeMillis"), -1L),
                DataAuthority.ReadVisibilityToken.fromPayload(mapValue(payload.get("visibilityToken")), null)
            )
        );
        return rankReader.quoteRanks(playerId, requirement).thenApply(this::rankResponse);
    }

    private Map<String, Object> mapPayload(MessageEnvelope envelope) {
        if (envelope.getPayload() == null || envelope.getPayload().isNull()) {
            return Map.of();
        }
        Map<?, ?> raw = gson.fromJson(envelope.getPayload().toString(), Map.class);
        return stringObjectMap(raw);
    }

    private DataAuthority.AuthorityCommand command(Map<String, Object> wire, MessageEnvelope envelope) {
        DataAuthority.CommandType type = DataAuthority.CommandType.valueOf(string(wire.get("commandType")));
        String scope = string(wire.get("scope"));
        DataAuthorityCommandContracts.validateRouteManifest(
            type,
            scope,
            mapValue(wire.get("route")),
            string(wire.get("routeManifestFingerprint"))
        );
        String topologyRejection = AuthorityTopologyEvidence.commandWireRejection(
            type,
            scope,
            mapValue(wire.get("route")),
            wire
        );
        if (topologyRejection != null) {
            throw new IllegalArgumentException(topologyRejection);
        }
        DataAuthority.CommandManifest manifest = new DataAuthority.CommandManifest(
            uuid(wire.get("commandId")),
            type,
            effectiveActorId(wire, envelope),
            scope,
            string(wire.get("idempotencyKey")),
            longValue(wire.get("deadlineEpochMillis"), 0L),
            string(wire.get("fencingToken")),
            longValue(wire.get("expectedRevision"), DataAuthority.ANY_REVISION),
            intValue(wire.get("schemaVersion"), DataAuthority.COMMAND_SCHEMA_VERSION),
            observedProvenance(wire.get("provenance"), envelope)
        );
        Map<String, Object> payload = stringObjectMap(mapValue(wire.get("payload")));

        DataAuthority.AuthorityCommand command = switch (type) {
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT -> new DataAuthority.PlayerProfileCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("username")),
                longValue(payload.get("timestamp"), System.currentTimeMillis()),
                string(payload.get("currentServer")),
                string(payload.get("currentProxy")),
                string(payload.get("lastIp")),
                string(payload.get("lastWorld")),
                string(payload.get("lastLocation")),
                string(payload.get("gamemode")),
                nullableInt(payload.get("level")),
                nullableFloat(payload.get("exp")),
                nullableDouble(payload.get("health")),
                nullableInt(payload.get("foodLevel")),
                string(payload.get("playtimeStartField"))
            );
            case START_SESSION, RENEW_SESSION, END_SESSION -> new DataAuthority.PlayerSessionCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("username")),
                nullableUuid(payload.get("sessionId")),
                longValue(payload.get("timestamp"), System.currentTimeMillis()),
                string(payload.get("currentServer")),
                string(payload.get("currentProxy")),
                string(payload.get("lastIp")),
                nullableInt(payload.get("protocolVersion")),
                string(payload.get("disconnectReason"))
            );
            case GRANT_RANK, REVOKE_RANK -> new DataAuthority.PlayerRankCommand(
                manifest,
                uuid(payload.get("playerId")),
                string(payload.get("primaryRank")),
                stringList(payload.get("ranks"))
            );
            case RECORD_MATCH_START, RECORD_MATCH_END -> new DataAuthority.MatchCommand(
                manifest,
                uuid(payload.get("matchId")),
                string(payload.get("familyId")),
                string(payload.get("mapId")),
                string(payload.get("serverId")),
                string(payload.get("slotId")),
                string(payload.get("state")),
                nullableLong(payload.get("startedAt")),
                nullableLong(payload.get("endedAt")),
                stringObjectMap(mapValue(payload.get("slotMetadata"))),
                participants(payload.get("participants"))
            );
        };
        DataAuthorityCommandContracts.validate(command);
        return command;
    }

    private String effectiveActorId(Map<String, Object> wire, MessageEnvelope envelope) {
        String claimedActor = string(wire.get("actorId"));
        if (AuthorityPrincipals.reservedPrincipal(claimedActor)) {
            return claimedActor;
        }
        return AuthorityPrincipals.nodePrincipal(firstKnown(envelope.getSenderId(), "unknown"));
    }

    private DataAuthority.CommandResult contractRejection(Map<String, Object> wire) {
        String actual = string(wire.get("contractFingerprint"));
        String expected = DataAuthorityCommandContracts.fingerprint();
        if (expected.equals(actual)) {
            return null;
        }
        UUID commandId = uuidOrSynthetic(wire.get("commandId"));
        long revision = safeLong(wire.get("expectedRevision"), DataAuthority.ANY_REVISION);
        return new DataAuthority.CommandResult(
            commandId,
            false,
            revision,
            DataAuthority.RejectionReason.VALIDATION_FAILED,
            "Authority command contract fingerprint mismatch: expected " + shortFingerprint(expected)
                + " but received " + shortFingerprint(actual)
        );
    }

    private DataAuthority.CommandResult commandRejection(Map<String, Object> wire, IllegalArgumentException exception) {
        UUID commandId = uuidOrSynthetic(wire.get("commandId"));
        long revision = safeLong(wire.get("expectedRevision"), DataAuthority.ANY_REVISION);
        DataAuthority.RejectionReason reason = exception instanceof DataAuthorityCommandContracts.CommandContractViolation violation
            ? violation.rejectionReason()
            : DataAuthority.RejectionReason.VALIDATION_FAILED;
        return new DataAuthority.CommandResult(
            commandId,
            false,
            revision,
            reason,
            exception.getMessage()
        );
    }

    private DataAuthority.CommandResult recordCommandRefusal(
        MessageEnvelope envelope,
        Map<String, Object> wire,
        DataAuthority.CommandResult result
    ) {
        try {
            commandRefusalRecorder.record(new CommandRefusal(
                firstKnown(envelope.getSenderId(), "unknown"),
                firstKnown(envelope.getTargetId(), "authority"),
                wire,
                result
            ));
            return result;
        } catch (RuntimeException exception) {
            return new DataAuthority.CommandResult(
                result.commandId(),
                false,
                result.revision(),
                DataAuthority.RejectionReason.STORE_UNAVAILABLE,
                "Authority command refusal evidence failed: " + exception.getMessage()
            );
        }
    }

    private boolean shouldRecordPrincipalRefusal(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        return !result.accepted()
            && result.rejectionReason() == DataAuthority.RejectionReason.INVALID_ACTOR
            && "message-bus-provider".equals(command.provenance().providerKind());
    }

    private DataAuthority.CommandResult withCommandRefusalReceipt(
        MessageEnvelope envelope,
        Map<String, Object> wire,
        DataAuthority.CommandResult result
    ) {
        if (result.accepted() || result.rejectionReason() == DataAuthority.RejectionReason.NONE) {
            return result;
        }
        String originNode = firstKnown(envelope.getSenderId(), "unknown");
        String targetNode = firstKnown(envelope.getTargetId(), "authority");
        Map<String, Object> payload = stringObjectMap(mapValue(wire.get("payload")));
        DataAuthority.CommandRefusalReceipt receipt = DataAuthority.CommandRefusalReceipt.create(
            "message-bus-provider",
            result.commandId(),
            firstKnown(string(wire.get("commandType")), "unknown"),
            firstKnown(string(wire.get("scope")), "unknown"),
            originNode,
            targetNode,
            "messagebus:" + originNode + "->" + targetNode,
            result.rejectionReason(),
            result.revision(),
            DataAuthorityCommandContracts.fingerprint(),
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            DataAuthority.CommandRefusalReceipt.payloadHash(payload),
            System.currentTimeMillis()
        );
        return result.withRefusalReceipt(receipt);
    }

    private DataAuthority.CommandProvenance observedProvenance(Object raw, MessageEnvelope envelope) {
        Map<String, Object> submitted = stringObjectMap(mapValue(raw));
        String originNode = firstKnown(envelope.getSenderId(), "unknown");
        String targetNode = firstKnown(envelope.getTargetId(), "authority");
        return new DataAuthority.CommandProvenance(
            originNode,
            "messagebus:" + originNode + "->" + targetNode,
            "message-bus-provider",
            intValue(submitted.get("contractVersion"), DataAuthority.COMMAND_SCHEMA_VERSION),
            AuthorityPrincipals.nodePrincipal(originNode)
        );
    }

    private Map<String, Object> commandResponse(DataAuthority.CommandResult result) {
        return new MapBuilder()
            .put("commandId", result.commandId().toString())
            .put("accepted", result.accepted())
            .put("revision", result.revision())
            .put("rejectionReason", result.rejectionReason().name())
            .put("message", result.message())
            .put("settlement", result.settlement().payload())
            .put("refusalReceipt", result.refusalReceipt() == null ? null : result.refusalReceipt().payload())
            .build();
    }

    private Map<String, Object> profileResponse(DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> read) {
        DataAuthority.QuotedRead<DataAuthority.PlayerProfileSnapshot> stamped = stampAuthorityBoot(read);
        DataAuthority.ReadQuote quote = stamped.quote();
        if (stamped.snapshot().isEmpty()) {
            return new MapBuilder()
                .put("found", false)
                .put("quote", quote.payload())
                .build();
        }
        DataAuthority.PlayerProfileSnapshot profile = stamped.snapshot().orElseThrow();
        return new MapBuilder()
            .put("found", true)
            .put("playerId", profile.playerId().toString())
            .put("username", profile.username())
            .put("normalizedUsername", profile.normalizedUsername())
            .put("online", profile.online())
            .put("currentServer", profile.currentServer())
            .put("currentProxy", profile.currentProxy())
            .put("totalPlaytimeMs", profile.totalPlaytimeMs())
            .put("profileData", profile.profileData())
            .put("revision", profile.revision())
            .put("watermark", profile.watermark().payload())
            .put("quote", quote.payload())
            .build();
    }

    private Map<String, Object> profileReadRejectionResponse(Map<String, Object> payload, String message) {
        String rawPlayerId = string(payload.get("playerId"));
        String aggregateScope = rawPlayerId == null || rawPlayerId.isBlank()
            ? "unknown"
            : "player:" + rawPlayerId;
        long requiredRevision = safeLong(payload.get("minimumRevision"), 0L);
        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            aggregateScope,
            "player_profile",
            requiredRevision,
            0L,
            DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE,
            null,
            message,
            DataAuthority.ReadProvenance.authority(authorityBootIdentity())
        );
        return new MapBuilder()
            .put("found", false)
            .put("rejectionReason", DataAuthority.RejectionReason.VALIDATION_FAILED.name())
            .put("message", message)
            .put("quote", quote.payload())
            .build();
    }

    private Map<String, Object> rankResponse(DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> read) {
        DataAuthority.QuotedRead<DataAuthority.PlayerRankSnapshot> stamped = stampAuthorityBoot(read);
        DataAuthority.ReadQuote quote = stamped.quote();
        if (stamped.snapshot().isEmpty()) {
            return new MapBuilder()
                .put("found", false)
                .put("quote", quote.payload())
                .build();
        }
        DataAuthority.PlayerRankSnapshot ranks = stamped.snapshot().orElseThrow();
        return new MapBuilder()
            .put("found", true)
            .put("playerId", ranks.playerId().toString())
            .put("primaryRank", ranks.primaryRank())
            .put("ranks", ranks.ranks())
            .put("revision", ranks.revision())
            .put("watermark", ranks.watermark().payload())
            .put("quote", quote.payload())
            .build();
    }

    private Map<String, Object> rankReadRejectionResponse(Map<String, Object> payload, String message) {
        String rawPlayerId = string(payload.get("playerId"));
        String aggregateScope = rawPlayerId == null || rawPlayerId.isBlank()
            ? "unknown"
            : "rank:player:" + rawPlayerId;
        long requiredRevision = safeLong(payload.get("minimumRevision"), 0L);
        DataAuthority.ReadQuote quote = new DataAuthority.ReadQuote(
            aggregateScope,
            "player_rank",
            requiredRevision,
            0L,
            DataAuthority.ReadQuoteStatus.UNKNOWN_OR_STALE,
            null,
            message,
            DataAuthority.ReadProvenance.authority(authorityBootIdentity())
        );
        return new MapBuilder()
            .put("found", false)
            .put("rejectionReason", DataAuthority.RejectionReason.VALIDATION_FAILED.name())
            .put("message", message)
            .put("quote", quote.payload())
            .build();
    }

    private <T> DataAuthority.QuotedRead<T> stampAuthorityBoot(DataAuthority.QuotedRead<T> read) {
        DataAuthority.ReadQuote quote = read.quote();
        DataAuthority.ReadProvenance provenance = quote.provenance().withAuthorityBoot(authorityBootIdentity());
        if (provenance.equals(quote.provenance())) {
            return read;
        }
        DataAuthority.ReadQuote stampedQuote = new DataAuthority.ReadQuote(
            quote.aggregateScope(),
            quote.projectionFamily(),
            quote.requiredRevision(),
            quote.observedRevision(),
            quote.status(),
            quote.watermark(),
            quote.message(),
            provenance,
            quote.deliveryReceipt()
        );
        return read.satisfied()
            ? DataAuthority.QuotedRead.satisfied(read.snapshot().orElseThrow(), stampedQuote)
            : DataAuthority.QuotedRead.unsatisfied(stampedQuote);
    }

    private DataAuthority.AuthorityBootIdentity authorityBootIdentity() {
        DataAuthority.AuthorityBootIdentity identity = authorityBootIdentitySupplier.get();
        return identity == null ? DataAuthority.AuthorityBootIdentity.unknown() : identity;
    }

    private void publishSnapshotInvalidation(DataAuthority.AuthorityCommand command, long committedRevision) {
        CompletionStage<Optional<AuthoritySnapshotInvalidation>> invalidation = invalidationFor(command);
        invalidation.whenComplete((packet, failure) -> {
            Optional<AuthoritySnapshotInvalidation> fallback =
                AuthoritySnapshotInvalidation.revisionFloorFor(command, committedRevision);
            if (failure != null || packet == null || packet.isEmpty()) {
                fallback.ifPresent(this::broadcastSnapshotInvalidation);
                return;
            }
            AuthoritySnapshotInvalidation value = packet.get();
            if (value.revision() < committedRevision) {
                fallback.ifPresent(this::broadcastSnapshotInvalidation);
                return;
            }
            broadcastSnapshotInvalidation(value);
        });
    }

    private void broadcastSnapshotInvalidation(AuthoritySnapshotInvalidation invalidation) {
        try {
            messageBus.broadcast(MessageBusAuthorityChannels.SNAPSHOT_INVALIDATION, invalidation.payload());
        } catch (RuntimeException ignored) {
            // Invalidation is advisory; command success is anchored to the committed snapshot.
        }
    }

    private CompletionStage<Optional<AuthoritySnapshotInvalidation>> invalidationFor(
        DataAuthority.AuthorityCommand command
    ) {
        if (command instanceof DataAuthority.PlayerRankCommand rankCommand) {
            return rankReader.findRanks(rankCommand.playerId())
                .thenApply(snapshot -> snapshot.flatMap(AuthoritySnapshotInvalidation::fromRankSnapshot));
        }
        if (command instanceof DataAuthority.PlayerProfileCommand profileCommand) {
            return profileReader.findProfile(profileCommand.playerId())
                .thenApply(snapshot -> snapshot.flatMap(AuthoritySnapshotInvalidation::fromProfileSnapshot));
        }
        if (command instanceof DataAuthority.PlayerSessionCommand sessionCommand) {
            return profileReader.findProfile(sessionCommand.playerId())
                .thenApply(snapshot -> snapshot.flatMap(AuthoritySnapshotInvalidation::fromProfileSnapshot));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private List<DataAuthority.MatchParticipant> participants(Object rawParticipants) {
        if (!(rawParticipants instanceof Iterable<?> rawValues)) {
            return List.of();
        }
        List<DataAuthority.MatchParticipant> participants = new ArrayList<>();
        for (Object raw : rawValues) {
            Map<String, Object> values = stringObjectMap(mapValue(raw));
            Object rawPlayerId = values.get("playerId");
            if (rawPlayerId == null) {
                continue;
            }
            participants.add(new DataAuthority.MatchParticipant(
                uuid(rawPlayerId),
                string(values.get("teamId")),
                nullableInt(values.get("placement")),
                string(values.get("state")),
                stringObjectMap(mapValue(values.get("stats")))
            ));
        }
        return participants;
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(key.toString(), value);
            }
        });
        return result;
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

    private static UUID nullableUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : uuid(value);
    }

    private static UUID uuidOrSynthetic(Object value) {
        try {
            return uuid(value);
        } catch (RuntimeException ignored) {
            String material = value == null ? "missing-command-id" : value.toString();
            return UUID.nameUUIDFromBytes(("authority-contract-rejection:" + material)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? fallback : Long.parseLong(value.toString());
    }

    private static long safeLong(Object value, long fallback) {
        try {
            return longValue(value, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : longValue(value, 0L);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(value.toString());
    }

    private static Integer nullableInt(Object value) {
        return value == null ? null : intValue(value, 0);
    }

    private static Float nullableFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return value == null ? null : Float.parseFloat(value.toString());
    }

    private static Double nullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? null : Double.parseDouble(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstKnown(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return "unknown";
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static final class MapBuilder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private MapBuilder put(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        private Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }

    @FunctionalInterface
    public interface CommandRefusalRecorder {
        void record(CommandRefusal refusal);
    }

    public record CommandRefusal(
        String originNode,
        String targetNode,
        Map<String, Object> wire,
        DataAuthority.CommandResult result
    ) {
        public CommandRefusal {
            originNode = firstKnown(originNode, "unknown");
            targetNode = firstKnown(targetNode, "authority");
            wire = wire == null || wire.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(wire));
            result = Objects.requireNonNull(result, "result");
        }
    }
}
