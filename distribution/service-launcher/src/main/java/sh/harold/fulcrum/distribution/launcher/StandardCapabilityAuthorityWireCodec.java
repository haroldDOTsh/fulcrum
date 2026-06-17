package sh.harold.fulcrum.distribution.launcher;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.SubjectId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.AuthorityDecisionStatus;
import sh.harold.fulcrum.data.authority.AuthorityRejectionReason;
import sh.harold.fulcrum.data.authority.StoredAuthorityDecision;
import sh.harold.fulcrum.standard.contracts.PlayerProfileContracts;
import sh.harold.fulcrum.standard.contracts.PunishmentContracts;
import sh.harold.fulcrum.standard.contracts.RankContracts;
import sh.harold.fulcrum.standard.profile.PlayerProfileReceipt;
import sh.harold.fulcrum.standard.profile.PlayerProfileSnapshot;
import sh.harold.fulcrum.standard.profile.PlayerProfileState;
import sh.harold.fulcrum.standard.profile.UpsertPlayerProfile;
import sh.harold.fulcrum.standard.punishment.ActivePunishmentSnapshot;
import sh.harold.fulcrum.standard.punishment.IssuePunishment;
import sh.harold.fulcrum.standard.punishment.PunishmentReceipt;
import sh.harold.fulcrum.standard.punishment.PunishmentState;
import sh.harold.fulcrum.standard.rank.EffectiveRankSnapshot;
import sh.harold.fulcrum.standard.rank.GrantRank;
import sh.harold.fulcrum.standard.rank.RankReceipt;
import sh.harold.fulcrum.standard.rank.RankState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class StandardCapabilityAuthorityWireCodec {
    static final String PLAYER_PROFILE_DOMAIN = "standard.player-profile";
    static final String RANK_DOMAIN = "standard.rank";
    static final String PUNISHMENT_DOMAIN = "standard.punishment";
    static final String UPSERT_PROFILE_COMMAND = "upsert-profile";
    static final String GRANT_RANK_COMMAND = "grant-rank";
    static final String ISSUE_PUNISHMENT_COMMAND = "issue-punishment";

    private static final Revision STATE_CODEC_REVISION = new Revision(0);

    private StandardCapabilityAuthorityWireCodec() {
    }

    static AuthorityCommand<UpsertPlayerProfile> decodePlayerProfileCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        return command(fields, record.key(), PlayerProfileContracts.CONTRACT.value(), decodePlayerProfilePayload(fields));
    }

    static String encodePlayerProfileCommand(AuthorityCommand<UpsertPlayerProfile> command) {
        Map<String, String> fields = commandFields(command);
        UpsertPlayerProfile payload = command.envelope().payload();
        fields.put("subjectId", payload.subjectId().value().toString());
        fields.put("displayName", escape(payload.displayName()));
        fields.put("observedAt", payload.observedAt().toString());
        fields.put("payloadExpectedRevision", Long.toString(payload.expectedRevision()));
        return lines(fields);
    }

    static String encodePlayerProfileState(PlayerProfileState state) {
        return Objects.requireNonNull(state, "state").wireValue(STATE_CODEC_REVISION);
    }

    static PlayerProfileState decodePlayerProfileState(String payload) {
        return PlayerProfileState.parse(payload);
    }

    static String encodePlayerProfileStoredDecision(
            StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> stored) {
        Map<String, String> fields = storedDecisionFields(stored);
        prefixed(fields, "state.", encodePlayerProfileState(stored.decision().state()));
        prefixed(fields, "response.", encodePlayerProfileReceipt(stored.decision().response()));
        return lines(fields);
    }

    static StoredAuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decodePlayerProfileStoredDecision(
            String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision = new AuthorityDecision<>(
                AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus")),
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf),
                new Revision(longValue(fields, "revision")),
                decodePlayerProfileState(unprefixed(fields, "state.")),
                decodePlayerProfileReceipt(unprefixed(fields, "response.")),
                List.of(),
                decodeTrace(fields),
                Boolean.parseBoolean(required(fields, "replayed")));
        return new StoredAuthorityDecision<>(required(fields, "payloadFingerprint"), decision);
    }

    static String encodePlayerProfileDecisionPayload(
            AuthorityDecision<PlayerProfileState, PlayerProfileReceipt> decision) {
        return encodePlayerProfileStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    static AuthorityCommand<GrantRank> decodeRankCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        return command(fields, record.key(), RankContracts.CONTRACT.value(), decodeRankPayload(fields));
    }

    static String encodeRankCommand(AuthorityCommand<GrantRank> command) {
        Map<String, String> fields = commandFields(command);
        GrantRank payload = command.envelope().payload();
        fields.put("subjectId", payload.subjectId().value().toString());
        fields.put("rankKey", escape(payload.rankKey()));
        fields.put("grantedAt", payload.grantedAt().toString());
        fields.put("payloadExpectedRevision", Long.toString(payload.expectedRevision()));
        return lines(fields);
    }

    static String encodeRankState(RankState state) {
        return Objects.requireNonNull(state, "state").wireValue(STATE_CODEC_REVISION);
    }

    static RankState decodeRankState(String payload) {
        return RankState.parse(payload);
    }

    static String encodeRankStoredDecision(StoredAuthorityDecision<RankState, RankReceipt> stored) {
        Map<String, String> fields = storedDecisionFields(stored);
        prefixed(fields, "state.", encodeRankState(stored.decision().state()));
        prefixed(fields, "response.", encodeRankReceipt(stored.decision().response()));
        return lines(fields);
    }

    static StoredAuthorityDecision<RankState, RankReceipt> decodeRankStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecision<RankState, RankReceipt> decision = new AuthorityDecision<>(
                AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus")),
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf),
                new Revision(longValue(fields, "revision")),
                decodeRankState(unprefixed(fields, "state.")),
                decodeRankReceipt(unprefixed(fields, "response.")),
                List.of(),
                decodeTrace(fields),
                Boolean.parseBoolean(required(fields, "replayed")));
        return new StoredAuthorityDecision<>(required(fields, "payloadFingerprint"), decision);
    }

    static String encodeRankDecisionPayload(AuthorityDecision<RankState, RankReceipt> decision) {
        return encodeRankStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    static AuthorityCommand<IssuePunishment> decodePunishmentCommand(ConsumerRecord<String, String> record) {
        Map<String, String> fields = fields(record.value());
        return command(fields, record.key(), PunishmentContracts.CONTRACT.value(), decodePunishmentPayload(fields));
    }

    static String encodePunishmentCommand(AuthorityCommand<IssuePunishment> command) {
        Map<String, String> fields = commandFields(command);
        IssuePunishment payload = command.envelope().payload();
        fields.put("subjectId", payload.subjectId().value().toString());
        fields.put("punishmentId", escape(payload.punishmentId()));
        fields.put("reason", escape(payload.reason()));
        fields.put("issuedAt", payload.issuedAt().toString());
        fields.put("expiresAt", payload.expiresAt().toString());
        fields.put("payloadExpectedRevision", Long.toString(payload.expectedRevision()));
        return lines(fields);
    }

    static String encodePunishmentState(PunishmentState state) {
        return Objects.requireNonNull(state, "state").wireValue(STATE_CODEC_REVISION);
    }

    static PunishmentState decodePunishmentState(String payload) {
        return PunishmentState.parse(payload);
    }

    static String encodePunishmentStoredDecision(
            StoredAuthorityDecision<PunishmentState, PunishmentReceipt> stored) {
        Map<String, String> fields = storedDecisionFields(stored);
        prefixed(fields, "state.", encodePunishmentState(stored.decision().state()));
        prefixed(fields, "response.", encodePunishmentReceipt(stored.decision().response()));
        return lines(fields);
    }

    static StoredAuthorityDecision<PunishmentState, PunishmentReceipt> decodePunishmentStoredDecision(String payload) {
        Map<String, String> fields = fields(payload);
        AuthorityDecision<PunishmentState, PunishmentReceipt> decision = new AuthorityDecision<>(
                AuthorityDecisionStatus.valueOf(required(fields, "decisionStatus")),
                optional(fields, "rejectionReason").map(AuthorityRejectionReason::valueOf),
                new Revision(longValue(fields, "revision")),
                decodePunishmentState(unprefixed(fields, "state.")),
                decodePunishmentReceipt(unprefixed(fields, "response.")),
                List.of(),
                decodeTrace(fields),
                Boolean.parseBoolean(required(fields, "replayed")));
        return new StoredAuthorityDecision<>(required(fields, "payloadFingerprint"), decision);
    }

    static String encodePunishmentDecisionPayload(AuthorityDecision<PunishmentState, PunishmentReceipt> decision) {
        return encodePunishmentStoredDecision(new StoredAuthorityDecision<>("recorded-decision", decision));
    }

    private static UpsertPlayerProfile decodePlayerProfilePayload(Map<String, String> fields) {
        requireCommand(fields, UPSERT_PROFILE_COMMAND);
        return new UpsertPlayerProfile(
                subjectId(required(fields, "subjectId")),
                unescape(required(fields, "displayName")),
                instant(fields, "observedAt"),
                payloadExpectedRevision(fields));
    }

    private static GrantRank decodeRankPayload(Map<String, String> fields) {
        requireCommand(fields, GRANT_RANK_COMMAND);
        return new GrantRank(
                subjectId(required(fields, "subjectId")),
                unescape(required(fields, "rankKey")),
                instant(fields, "grantedAt"),
                payloadExpectedRevision(fields));
    }

    private static IssuePunishment decodePunishmentPayload(Map<String, String> fields) {
        requireCommand(fields, ISSUE_PUNISHMENT_COMMAND);
        return new IssuePunishment(
                subjectId(required(fields, "subjectId")),
                unescape(required(fields, "punishmentId")),
                unescape(required(fields, "reason")),
                instant(fields, "issuedAt"),
                instant(fields, "expiresAt"),
                payloadExpectedRevision(fields));
    }

    private static <C extends sh.harold.fulcrum.api.contract.CommandPayload> AuthorityCommand<C> command(
            Map<String, String> fields,
            String recordKey,
            String defaultContract,
            C payload) {
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId(required(fields, "commandId")),
                        new IdempotencyKey(required(fields, "idempotencyKey")),
                        new PrincipalId(required(fields, "principalId")),
                        new AggregateId(optional(fields, "aggregateId").orElse(recordKey)),
                        new ContractName(optional(fields, "contractName").orElse(defaultContract)),
                        new CommandName(required(fields, "commandName")),
                        decodeTrace(fields),
                        optionalInstant(fields, "deadlineAt"),
                        payload),
                new PrincipalId(required(fields, "authenticatedPrincipal")),
                longValue(fields, "fencingEpoch"),
                optionalRevision(fields, "expectedRevision"),
                required(fields, "payloadFingerprint"),
                instant(fields, "receivedAt"));
    }

    private static Map<String, String> commandFields(
            AuthorityCommand<? extends sh.harold.fulcrum.api.contract.CommandPayload> command) {
        Objects.requireNonNull(command, "command");
        Map<String, String> fields = new LinkedHashMap<>();
        CommandEnvelope<? extends sh.harold.fulcrum.api.contract.CommandPayload> envelope = command.envelope();
        fields.put("commandId", envelope.commandId().value());
        fields.put("idempotencyKey", envelope.idempotencyKey().value());
        fields.put("principalId", envelope.principalId().value());
        fields.put("aggregateId", envelope.aggregateId().value());
        fields.put("contractName", envelope.contractName().value());
        fields.put("commandName", envelope.commandName().value());
        encodeTrace(fields, envelope.traceEnvelope());
        fields.put("deadlineAt", envelope.deadlineAt().map(Instant::toString).orElse(""));
        fields.put("authenticatedPrincipal", command.authenticatedPrincipal().value());
        fields.put("fencingEpoch", Long.toString(command.fencingEpoch()));
        fields.put("expectedRevision", command.expectedRevision().map(value -> Long.toString(value.value())).orElse(""));
        fields.put("payloadFingerprint", command.payloadFingerprint());
        fields.put("receivedAt", command.receivedAt().toString());
        return fields;
    }

    private static Map<String, String> storedDecisionFields(StoredAuthorityDecision<?, ?> stored) {
        Objects.requireNonNull(stored, "stored");
        AuthorityDecision<?, ?> decision = stored.decision();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payloadFingerprint", stored.payloadFingerprint());
        fields.put("decisionStatus", decision.status().name());
        fields.put("rejectionReason", decision.rejectionReason().map(Enum::name).orElse(""));
        fields.put("revision", Long.toString(decision.revision().value()));
        fields.put("replayed", Boolean.toString(decision.replayed()));
        encodeTrace(fields, decision.traceEnvelope());
        return fields;
    }

    private static String encodePlayerProfileReceipt(PlayerProfileReceipt receipt) {
        Map<String, String> fields = receiptFields(receipt.accepted(), receipt.revision(), receipt.fencingEpoch(),
                receipt.idempotencyKey(), receipt.commandId(), receipt.rejectionReason());
        receipt.snapshot().ifPresent(snapshot -> encodePlayerProfileSnapshot(fields, "snapshot.", snapshot));
        return lines(fields);
    }

    private static PlayerProfileReceipt decodePlayerProfileReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "accepted"))) {
            return PlayerProfileReceipt.rejected(required(fields, "rejectionReason"));
        }
        return PlayerProfileReceipt.accepted(
                decodePlayerProfileSnapshot(fields, "snapshot."),
                new Revision(longValue(fields, "revision")),
                longValue(fields, "fencingEpoch"),
                required(fields, "idempotencyKey"),
                required(fields, "commandId"));
    }

    private static String encodeRankReceipt(RankReceipt receipt) {
        Map<String, String> fields = receiptFields(receipt.accepted(), receipt.revision(), receipt.fencingEpoch(),
                receipt.idempotencyKey(), receipt.commandId(), receipt.rejectionReason());
        receipt.snapshot().ifPresent(snapshot -> encodeRankSnapshot(fields, "snapshot.", snapshot));
        return lines(fields);
    }

    private static RankReceipt decodeRankReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "accepted"))) {
            return RankReceipt.rejected(required(fields, "rejectionReason"));
        }
        return RankReceipt.accepted(
                decodeRankSnapshot(fields, "snapshot."),
                new Revision(longValue(fields, "revision")),
                longValue(fields, "fencingEpoch"),
                required(fields, "idempotencyKey"),
                required(fields, "commandId"));
    }

    private static String encodePunishmentReceipt(PunishmentReceipt receipt) {
        Map<String, String> fields = receiptFields(receipt.accepted(), receipt.revision(), receipt.fencingEpoch(),
                receipt.idempotencyKey(), receipt.commandId(), receipt.rejectionReason());
        receipt.snapshot().ifPresent(snapshot -> encodePunishmentSnapshot(fields, "snapshot.", snapshot));
        return lines(fields);
    }

    private static PunishmentReceipt decodePunishmentReceipt(String payload) {
        Map<String, String> fields = fields(payload);
        if (!Boolean.parseBoolean(required(fields, "accepted"))) {
            return PunishmentReceipt.rejected(required(fields, "rejectionReason"));
        }
        return PunishmentReceipt.accepted(
                decodePunishmentSnapshot(fields, "snapshot."),
                new Revision(longValue(fields, "revision")),
                longValue(fields, "fencingEpoch"),
                required(fields, "idempotencyKey"),
                required(fields, "commandId"));
    }

    private static Map<String, String> receiptFields(
            boolean accepted,
            Optional<Revision> revision,
            long fencingEpoch,
            String idempotencyKey,
            String commandId,
            Optional<String> rejectionReason) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("accepted", Boolean.toString(accepted));
        fields.put("revision", revision.map(value -> Long.toString(value.value())).orElse(""));
        fields.put("fencingEpoch", Long.toString(fencingEpoch));
        fields.put("idempotencyKey", idempotencyKey);
        fields.put("commandId", commandId);
        fields.put("rejectionReason", rejectionReason.orElse(""));
        return fields;
    }

    private static void encodePlayerProfileSnapshot(
            Map<String, String> fields,
            String prefix,
            PlayerProfileSnapshot snapshot) {
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "displayName", escape(snapshot.displayName()));
        fields.put(prefix + "updatedBy", snapshot.updatedBy().value());
        fields.put(prefix + "observedAt", snapshot.observedAt().toString());
    }

    private static PlayerProfileSnapshot decodePlayerProfileSnapshot(Map<String, String> fields, String prefix) {
        return new PlayerProfileSnapshot(
                subjectId(required(fields, prefix + "subjectId")),
                unescape(required(fields, prefix + "displayName")),
                new PrincipalId(required(fields, prefix + "updatedBy")),
                instant(fields, prefix + "observedAt"));
    }

    private static void encodeRankSnapshot(Map<String, String> fields, String prefix, EffectiveRankSnapshot snapshot) {
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "primaryRankKey", escape(snapshot.primaryRankKey()));
        fields.put(prefix + "permissions", escape(snapshot.permissions()));
        fields.put(prefix + "updatedBy", snapshot.updatedBy().value());
        fields.put(prefix + "updatedAt", snapshot.updatedAt().toString());
    }

    private static EffectiveRankSnapshot decodeRankSnapshot(Map<String, String> fields, String prefix) {
        return new EffectiveRankSnapshot(
                subjectId(required(fields, prefix + "subjectId")),
                unescape(required(fields, prefix + "primaryRankKey")),
                unescape(required(fields, prefix + "permissions")),
                new PrincipalId(required(fields, prefix + "updatedBy")),
                instant(fields, prefix + "updatedAt"));
    }

    private static void encodePunishmentSnapshot(
            Map<String, String> fields,
            String prefix,
            ActivePunishmentSnapshot snapshot) {
        fields.put(prefix + "subjectId", snapshot.subjectId().value().toString());
        fields.put(prefix + "punishmentId", escape(snapshot.punishmentId()));
        fields.put(prefix + "reason", escape(snapshot.reason()));
        fields.put(prefix + "issuedBy", snapshot.issuedBy().value());
        fields.put(prefix + "issuedAt", snapshot.issuedAt().toString());
        fields.put(prefix + "expiresAt", snapshot.expiresAt().toString());
    }

    private static ActivePunishmentSnapshot decodePunishmentSnapshot(Map<String, String> fields, String prefix) {
        return new ActivePunishmentSnapshot(
                subjectId(required(fields, prefix + "subjectId")),
                unescape(required(fields, prefix + "punishmentId")),
                unescape(required(fields, prefix + "reason")),
                new PrincipalId(required(fields, prefix + "issuedBy")),
                instant(fields, prefix + "issuedAt"),
                instant(fields, prefix + "expiresAt"));
    }

    private static void encodeTrace(Map<String, String> fields, TraceEnvelope trace) {
        fields.put("traceId", trace.traceId());
        fields.put("spanId", trace.spanId());
        fields.put("parentSpanId", trace.parentSpanId().orElse(""));
        fields.put("traceCreatedAt", trace.createdAt().toString());
        fields.put("originService", trace.originService());
        fields.put("originInstanceId", trace.originInstanceId().value());
    }

    private static TraceEnvelope decodeTrace(Map<String, String> fields) {
        return new TraceEnvelope(
                required(fields, "traceId"),
                required(fields, "spanId"),
                optional(fields, "parentSpanId"),
                optionalInstant(fields, "traceCreatedAt").orElseGet(() -> instant(fields, "receivedAt")),
                required(fields, "originService"),
                new InstanceId(required(fields, "originInstanceId")));
    }

    private static void prefixed(Map<String, String> target, String prefix, String payload) {
        fields(payload).forEach((key, value) -> target.put(prefix + key, value));
    }

    private static String unprefixed(Map<String, String> source, String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                values.put(key.substring(prefix.length()), value);
            }
        });
        return lines(values);
    }

    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return fields;
        }
        for (String line : payload.split("\\R")) {
            int separator = separatorIndex(line);
            if (separator < 1) {
                throw new IllegalArgumentException("Malformed standard capability authority wire line: " + line);
            }
            fields.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return fields;
    }

    private static String lines(Map<String, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((key, value) -> builder.append(key).append('=').append(value == null ? "" : value).append('\n'));
        return builder.toString();
    }

    private static int separatorIndex(String line) {
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '=') {
                return index;
            }
        }
        return -1;
    }

    private static void requireCommand(Map<String, String> fields, String expectedCommand) {
        String commandName = required(fields, "commandName");
        if (!expectedCommand.equals(commandName)) {
            throw new IllegalArgumentException("Unsupported standard capability command " + commandName);
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing standard capability authority wire field " + key);
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static long payloadExpectedRevision(Map<String, String> fields) {
        return optionalLong(fields, "payloadExpectedRevision")
                .or(() -> optionalLong(fields, "expectedRevision"))
                .orElse(0L);
    }

    private static long longValue(Map<String, String> fields, String key) {
        return Long.parseLong(required(fields, key));
    }

    private static Optional<Long> optionalLong(Map<String, String> fields, String key) {
        return optional(fields, key).map(Long::parseLong);
    }

    private static Optional<Revision> optionalRevision(Map<String, String> fields, String key) {
        return optionalLong(fields, key).map(Revision::new);
    }

    private static Instant instant(Map<String, String> fields, String key) {
        return Instant.parse(required(fields, key));
    }

    private static Optional<Instant> optionalInstant(Map<String, String> fields, String key) {
        return optional(fields, key).map(Instant::parse);
    }

    private static SubjectId subjectId(String value) {
        return new SubjectId(UUID.fromString(value));
    }

    private static String escape(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("=", "\\=");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    builder.append(current);
                }
                continue;
            }
            switch (current) {
                case 'n' -> builder.append('\n');
                case '=', '\\' -> builder.append(current);
                default -> builder.append(current);
            }
            escaped = false;
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
