package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Executable command contract manifest for authority command frames.
 */
public final class DataAuthorityCommandContracts {
    private static final Map<String, CommandContract> CONTRACTS_BY_DECLARATION_ID = contractsByDeclarationId();
    private static final Map<DataAuthority.CommandType, CommandContract> CONTRACTS_BY_TYPE =
        contractsByType(CONTRACTS_BY_DECLARATION_ID);
    private static final String FINGERPRINT = fingerprint(CONTRACTS_BY_DECLARATION_ID);
    private static final String ROUTE_MANIFEST_FINGERPRINT = routeManifestFingerprint(CONTRACTS_BY_DECLARATION_ID);
    private static final Map<String, String> ROUTE_PARTITION_KEY_VECTORS = routePartitionKeyVectors(
        CONTRACTS_BY_DECLARATION_ID);
    private static final Map<String, String> COMMAND_TOPICS_BY_TYPE = routeVectors(CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::commandTopic);
    private static final Map<String, String> RESPONSE_TOPICS_BY_TYPE = routeVectors(CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::responseTopic);
    private static final Map<String, String> EVENT_TOPICS_BY_TYPE = routeVectors(CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::eventTopic);
    private static final Map<String, String> STATE_TOPICS_BY_TYPE = routeVectors(CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::stateTopic);
    private static final Set<String> COMMAND_TOPICS = commandTopics(CONTRACTS_BY_DECLARATION_ID);

    private DataAuthorityCommandContracts() {
    }

    public static Map<DataAuthority.CommandType, CommandContract> all() {
        return CONTRACTS_BY_TYPE;
    }

    public static Map<String, CommandContract> allByDeclarationId() {
        return CONTRACTS_BY_DECLARATION_ID;
    }

    public static CommandContract contract(DataAuthority.CommandType type) {
        Objects.requireNonNull(type, "type");
        return contractByDeclarationId(type.name());
    }

    public static CommandContract contractByDeclarationId(String declarationId) {
        CommandContract contract = CONTRACTS_BY_DECLARATION_ID.get(declarationId);
        if (contract == null) {
            throw new IllegalArgumentException("No authority command contract for " + declarationId);
        }
        return contract;
    }

    public static CommandDeliveryMode deliveryMode(DataAuthority.CommandType type) {
        return contract(type).deliveryMode();
    }

    public static CommandRevisionPolicy revisionPolicy(DataAuthority.CommandType type) {
        return contract(type).revisionPolicy();
    }

    public static String fingerprint() {
        return FINGERPRINT;
    }

    public static String routeManifestFingerprint() {
        return ROUTE_MANIFEST_FINGERPRINT;
    }

    public static Map<String, String> routePartitionKeyVectors() {
        return ROUTE_PARTITION_KEY_VECTORS;
    }

    public static Map<String, String> commandTopicsByType() {
        return COMMAND_TOPICS_BY_TYPE;
    }

    public static Map<String, String> responseTopicsByType() {
        return RESPONSE_TOPICS_BY_TYPE;
    }

    public static Map<String, String> eventTopicsByType() {
        return EVENT_TOPICS_BY_TYPE;
    }

    public static Map<String, String> stateTopicsByType() {
        return STATE_TOPICS_BY_TYPE;
    }

    public static Set<String> commandTopics() {
        return COMMAND_TOPICS;
    }

    public static Map<String, Object> routePayload(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        validate(command);
        return AuthorityCommandRoute.fromCommand(command).payload();
    }

    public static void validateRouteManifest(
        DataAuthority.CommandType type,
        String scope,
        Map<?, ?> rawRoute,
        String receivedRouteManifestFingerprint
    ) {
        String actualFingerprint = receivedRouteManifestFingerprint == null
            ? ""
            : receivedRouteManifestFingerprint;
        if (!ROUTE_MANIFEST_FINGERPRINT.equals(actualFingerprint)) {
            throw new IllegalArgumentException(
                "Authority command route manifest fingerprint mismatch: expected "
                    + shortFingerprint(ROUTE_MANIFEST_FINGERPRINT)
                    + " but received " + shortFingerprint(actualFingerprint)
            );
        }
        if (rawRoute == null || rawRoute.isEmpty()) {
            throw new IllegalArgumentException("Command " + type + " route manifest is required");
        }
        requireRouteField(type, rawRoute, "domain");
        requireRouteField(type, rawRoute, "commandTopic");
        requireRouteField(type, rawRoute, "responseTopic");
        requireRouteField(type, rawRoute, "eventTopic");
        requireRouteField(type, rawRoute, "stateTopic");
        requireRouteField(type, rawRoute, "partitionKey");
        validateRoute(type, scope, AuthorityCommandRoute.fromPayload(rawRoute, type, scope));
    }

    public static void validate(DataAuthority.AuthorityCommand command) {
        Objects.requireNonNull(command, "command");
        CommandContract contract = contract(command.type());
        if (!contract.commandClass().isInstance(command)) {
            throw new IllegalArgumentException(
                "Command " + command.type() + " must decode as " + contract.commandClass().getSimpleName()
            );
        }
        DataAuthority.CommandManifest manifest = command.manifest();
        validateManifestContract(command.type(), manifest.schemaVersion(), command.provenance().contractVersion());
        validate(
            command.type(),
            manifest.schemaVersion(),
            command.provenance().contractVersion(),
            command.scope(),
            AuthorityCommandRoute.fromCommand(command),
            command.expectedRevision(),
            AuthorityCommandPayloads.payload(command)
        );
    }

    public static void validateSettlement(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandSettlement settlement
    ) {
        Objects.requireNonNull(command, "command");
        if (settlement == null || !settlement.settled()) {
            return;
        }

        AuthorityCommandRoute route = AuthorityCommandRoute.fromCommand(command);
        requireSettlementField("commandDomain", route.domain(), settlement.commandDomain());
        requireSettlementField("commandTopic", route.commandTopic(), settlement.commandTopic());
        requireSettlementField("responseTopic", route.responseTopic(), settlement.responseTopic());
        requireSettlementField("eventTopic", route.eventTopic(), settlement.eventTopic());
        requireSettlementField("stateTopic", route.stateTopic(), settlement.stateTopic());
        requireSettlementField("partitionKey", route.partitionKey(), settlement.partitionKey());
        requireSettlementField("idempotencyKey", command.idempotencyKey(), settlement.idempotencyKey());
        requireSettlementField("expectedRevision", command.expectedRevision(), settlement.expectedRevision());

        DataAuthority.SnapshotWatermark watermark = settlement.watermark();
        requireSettlementField("watermark.sourceProvider", settlement.sourceProvider(), watermark.sourceProvider());
        requireSettlementField("watermark.aggregateScope", command.scope(), watermark.aggregateScope());
        requireSettlementField(
            "watermark.aggregateType",
            AuthorityDomainDeclarations.command(command.type()).projectionFamily(),
            watermark.aggregateType()
        );
        requireSettlementField("watermark.commandDomain", route.domain(), watermark.commandDomain());
        requireSettlementField("watermark.stateTopic", route.stateTopic(), watermark.stateTopic());
        requireSettlementField("watermark.partitionKey", route.partitionKey(), watermark.partitionKey());
        requireSettlementField("watermark.sourceCommandId", command.commandId(), watermark.sourceCommandId());
    }

    public static void validateResult(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(result, "result");
        if (!command.commandId().equals(result.commandId())) {
            throw new IllegalStateException(
                "Authority command response commandId mismatch: expected "
                    + command.commandId() + " but received " + result.commandId()
            );
        }
        if (result.accepted()) {
            requireResultField("accepted result rejectionReason", DataAuthority.RejectionReason.NONE,
                result.rejectionReason());
            if (!result.settlement().settled()) {
                throw new IllegalStateException("Authority command accepted result requires a settled receipt");
            }
            validateSettlement(command, result.settlement());
            requireResultField("settlement watermark.sourceRevision", result.revision(),
                result.settlement().watermark().sourceRevision());
            return;
        }
        if (result.rejectionReason() == DataAuthority.RejectionReason.NONE) {
            throw new IllegalStateException("Authority command rejected result requires a stable rejectionReason");
        }
        if (result.settlement().settled()) {
            throw new IllegalStateException("Authority command rejected result must not carry a settled receipt");
        }
        validateRefusalReceipt(command, result);
    }

    private static void validateRefusalReceipt(
        DataAuthority.AuthorityCommand command,
        DataAuthority.CommandResult result
    ) {
        DataAuthority.CommandRefusalReceipt receipt = result.refusalReceipt();
        if (receipt == null || !receipt.refused()) {
            throw new IllegalStateException("Authority command rejected result requires a refusal receipt");
        }
        requireSettlementField("refusalReceipt.commandId", command.commandId(), receipt.commandId());
        requireSettlementField("refusalReceipt.commandType", command.type().name(), receipt.commandType());
        requireSettlementField("refusalReceipt.aggregateScope", command.scope(), receipt.aggregateScope());
        requireSettlementField("refusalReceipt.rejectionReason", result.rejectionReason(), receipt.rejectionReason());
        requireSettlementField("refusalReceipt.resultRevision", result.revision(), receipt.resultRevision());
        requireSettlementField("refusalReceipt.contractFingerprint", fingerprint(), receipt.contractFingerprint());
        requireSettlementField(
            "refusalReceipt.routeManifestFingerprint",
            routeManifestFingerprint(),
            receipt.routeManifestFingerprint()
        );
        requireSettlementField(
            "refusalReceipt.payloadHash",
            DataAuthority.CommandRefusalReceipt.payloadHash(AuthorityCommandPayloads.payload(command)),
            receipt.payloadHash()
        );
    }

    static void validate(
        DataAuthority.CommandType type,
        int schemaVersion,
        int provenanceContractVersion,
        String scope,
        AuthorityCommandRoute route,
        long expectedRevision,
        Map<String, Object> payload
    ) {
        CommandContract contract = contract(type);
        validateManifestContract(type, schemaVersion, provenanceContractVersion);
        validateRoute(type, scope, route);
        validatePayload(contract, payload);
        validateAggregateScope(contract, scope, payload);
        validateRevisionPolicy(contract, expectedRevision);
    }

    private static void validateRoute(
        DataAuthority.CommandType type,
        String scope,
        AuthorityCommandRoute route
    ) {
        CommandContract contract = contract(type);
        AuthorityCommandRoute expectedRoute = AuthorityCommandRoute.from(type, scope);
        if (route != null && !contract.domain().equals(route.domain())) {
            throw new IllegalArgumentException(
                "Command " + type + " route domain " + route.domain()
                    + " does not match contract domain " + contract.domain()
            );
        }
        if (route != null && !expectedRoute.commandTopic().equals(route.commandTopic())) {
            throw new IllegalArgumentException("Command " + type + " command topic does not match contract route");
        }
        if (route != null && !expectedRoute.responseTopic().equals(route.responseTopic())) {
            throw new IllegalArgumentException("Command " + type + " response topic does not match contract route");
        }
        if (route != null && !expectedRoute.eventTopic().equals(route.eventTopic())) {
            throw new IllegalArgumentException("Command " + type + " event topic does not match contract route");
        }
        if (route != null && !expectedRoute.stateTopic().equals(route.stateTopic())) {
            throw new IllegalArgumentException("Command " + type + " state topic does not match contract route");
        }
        if (route != null && !expectedRoute.partitionKey().equals(route.partitionKey())) {
            throw new IllegalArgumentException("Command " + type + " partition key does not match command scope");
        }
    }

    private static void requireSettlementField(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                "Authority command settlement " + field + " mismatch: expected "
                    + expected + " but received " + actual
            );
        }
    }

    private static void requireResultField(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(
                "Authority command " + field + " mismatch: expected "
                    + expected + " but received " + actual
            );
        }
    }

    private static void validateManifestContract(
        DataAuthority.CommandType type,
        int schemaVersion,
        int provenanceContractVersion
    ) {
        if (schemaVersion != DataAuthority.COMMAND_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Command " + type + " schema version " + schemaVersion
                    + " is not supported by authority contract version "
                    + DataAuthority.COMMAND_SCHEMA_VERSION
            );
        }
        if (provenanceContractVersion != DataAuthority.COMMAND_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Command " + type + " provenance contract version " + provenanceContractVersion
                    + " is not supported by authority contract version "
                    + DataAuthority.COMMAND_SCHEMA_VERSION
            );
        }
    }

    private static void validatePayload(CommandContract contract, Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        for (String requiredField : contract.requiredPayloadFields()) {
            Object value = safePayload.get(requiredField);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException(
                    "Command " + contract.type() + " is missing required payload field " + requiredField
                );
            }
        }
        Set<String> allowedFields = contract.allowedPayloadFields();
        for (String field : safePayload.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(
                    "Command " + contract.type() + " payload field " + field + " is not in the command contract"
                );
            }
        }
    }

    private static void validateRevisionPolicy(CommandContract contract, long expectedRevision) {
        if (contract.revisionPolicy() == CommandRevisionPolicy.COMPARE_REQUIRED
            && expectedRevision == DataAuthority.ANY_REVISION) {
            throw new CommandContractViolation(
                DataAuthority.RejectionReason.STALE_REVISION,
                "Command " + contract.type()
                    + " requires a concrete expectedRevision; ANY_REVISION is only valid for blind writes"
            );
        }
    }

    private static void validateAggregateScope(
        CommandContract contract,
        String scope,
        Map<String, Object> payload
    ) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        Object aggregateId = safePayload.get(contract.aggregateIdField());
        if (aggregateId == null || aggregateId.toString().isBlank()) {
            return;
        }
        String expectedScope = contract.aggregateScopePrefix() + aggregateId;
        if (!expectedScope.equals(scope)) {
            throw new CommandContractViolation(
                DataAuthority.RejectionReason.INVALID_SCOPE,
                "Command " + contract.type() + " scope does not match payload aggregate id: expected "
                    + expectedScope + " but was " + scope
            );
        }
    }

    private static Map<String, CommandContract> contractsByDeclarationId() {
        Map<String, CommandContract> values = new LinkedHashMap<>();
        for (AuthorityDomainDeclarations.DomainDeclaration domain : AuthorityDomainDeclarations.all().values()) {
            for (AuthorityDomainDeclarations.CommandDeclaration command : domain.commands()) {
                CommandContract previous = values.put(command.declarationId(), new CommandContract(
                    command.declarationId(),
                    command.type(),
                    command.commandClass(),
                    domain.domain(),
                    command.deliveryMode(),
                    command.revisionPolicy(),
                    singleStore(domain.commandLogStores(), "commandLogStore"),
                    singleStore(domain.hotProjectionStores(), "hotProjectionStore"),
                    singleStore(domain.historyStores(), "historyStore"),
                    singleStore(domain.cacheStores(), "cacheStore"),
                    command.aggregateScopePrefix(),
                    command.aggregateIdField(),
                    command.requiredPayloadFields(),
                    command.allowedPayloadFields()
                ));
                if (previous != null) {
                    throw new IllegalStateException("Duplicate command declaration for " + command.declarationId());
                }
            }
        }
        return Map.copyOf(values);
    }

    private static Map<DataAuthority.CommandType, CommandContract> contractsByType(
        Map<String, CommandContract> contracts
    ) {
        Map<DataAuthority.CommandType, CommandContract> values = new LinkedHashMap<>();
        for (CommandContract contract : contracts.values()) {
            CommandContract previous = values.put(contract.type(), contract);
            if (previous != null) {
                throw new IllegalStateException("Duplicate legacy command type for " + contract.type());
            }
        }
        return Map.copyOf(values);
    }

    private static String singleStore(java.util.List<String> stores, String field) {
        if (stores.size() != 1) {
            throw new IllegalStateException(field + " requires one store but had " + stores);
        }
        return stores.iterator().next();
    }

    private static String fingerprint(Map<String, CommandContract> contracts) {
        StringBuilder material = new StringBuilder()
            .append("commandSchemaVersion=").append(DataAuthority.COMMAND_SCHEMA_VERSION).append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> material
                .append(contract.declarationId()).append('|')
                .append(contract.commandClass().getName()).append('|')
                .append(contract.domain()).append('|')
                .append(contract.deliveryMode().name()).append('|')
                .append(contract.revisionPolicy().name()).append('|')
                .append(contract.commandLogStore()).append('|')
                .append(contract.hotProjectionStore()).append('|')
                .append(contract.historyStore()).append('|')
                .append(contract.cacheStore()).append('|')
                .append(contract.aggregateScopePrefix()).append('|')
                .append(contract.aggregateIdField()).append('|')
                .append(String.join(",", contract.requiredPayloadFields().stream().sorted().toList())).append('|')
                .append(String.join(",", contract.allowedPayloadFields().stream().sorted().toList()))
                .append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority command contracts", exception);
        }
    }

    private static String routeManifestFingerprint(Map<String, CommandContract> contracts) {
        StringBuilder material = new StringBuilder()
            .append("routeManifestSchemaVersion=").append(DataAuthority.COMMAND_SCHEMA_VERSION).append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.from(contract.type(), sampleScope);
                material
                    .append(contract.declarationId()).append('|')
                    .append(contract.domain()).append('|')
                    .append(contract.aggregateScopePrefix()).append('|')
                    .append(route.domain()).append('|')
                    .append(route.commandTopic()).append('|')
                    .append(route.responseTopic()).append('|')
                    .append(route.eventTopic()).append('|')
                    .append(route.stateTopic()).append('|')
                    .append(route.partitionKey())
                    .append('\n');
            });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority command route manifest", exception);
        }
    }

    private static Map<String, String> routePartitionKeyVectors(
        Map<String, CommandContract> contracts
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.from(contract.type(), sampleScope);
                values.put(contract.declarationId(), sampleScope + "=>" + route.partitionKey());
            });
        return Map.copyOf(values);
    }

    private static Map<String, String> routeVectors(
        Map<String, CommandContract> contracts,
        Function<AuthorityCommandRoute, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.from(contract.type(), sampleScope);
                values.put(contract.declarationId(), extractor.apply(route));
            });
        return Map.copyOf(values);
    }

    private static Set<String> commandTopics(Map<String, CommandContract> contracts) {
        Set<String> values = new LinkedHashSet<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                values.add(AuthorityCommandRoute.from(contract.type(), sampleScope).commandTopic());
            });
        return Set.copyOf(values);
    }

    private static void requireRouteField(DataAuthority.CommandType type, Map<?, ?> rawRoute, String field) {
        Object value = rawRoute.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Command " + type + " route manifest is missing " + field);
        }
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "missing";
        }
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    public enum CommandDeliveryMode {
        ASYNC_DURABLE,
        SYNC_INTERACTIVE
    }

    public enum CommandRevisionPolicy {
        BLIND_ALLOWED,
        COMPARE_REQUIRED
    }

    public record CommandContract(
        String declarationId,
        DataAuthority.CommandType type,
        Class<? extends DataAuthority.AuthorityCommand> commandClass,
        String domain,
        CommandDeliveryMode deliveryMode,
        CommandRevisionPolicy revisionPolicy,
        String commandLogStore,
        String hotProjectionStore,
        String historyStore,
        String cacheStore,
        String aggregateScopePrefix,
        String aggregateIdField,
        Set<String> requiredPayloadFields,
        Set<String> allowedPayloadFields
    ) {
        public CommandContract {
            if (declarationId == null || declarationId.isBlank()) {
                throw new IllegalArgumentException("declarationId is required");
            }
            type = Objects.requireNonNull(type, "type");
            commandClass = Objects.requireNonNull(commandClass, "commandClass");
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("domain is required");
            }
            deliveryMode = Objects.requireNonNull(deliveryMode, "deliveryMode");
            revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
            if (commandLogStore == null || commandLogStore.isBlank()) {
                throw new IllegalArgumentException("commandLogStore is required");
            }
            if (hotProjectionStore == null || hotProjectionStore.isBlank()) {
                throw new IllegalArgumentException("hotProjectionStore is required");
            }
            if (historyStore == null || historyStore.isBlank()) {
                throw new IllegalArgumentException("historyStore is required");
            }
            if (cacheStore == null || cacheStore.isBlank()) {
                throw new IllegalArgumentException("cacheStore is required");
            }
            if (aggregateScopePrefix == null || aggregateScopePrefix.isBlank()) {
                throw new IllegalArgumentException("aggregateScopePrefix is required");
            }
            if (aggregateIdField == null || aggregateIdField.isBlank()) {
                throw new IllegalArgumentException("aggregateIdField is required");
            }
            requiredPayloadFields = requiredPayloadFields == null ? Set.of() : Set.copyOf(requiredPayloadFields);
            allowedPayloadFields = allowedPayloadFields == null ? Set.of() : Set.copyOf(allowedPayloadFields);
            if (!requiredPayloadFields.contains(aggregateIdField)) {
                throw new IllegalArgumentException(type + " aggregate id field must be required");
            }
            if (!allowedPayloadFields.containsAll(requiredPayloadFields)) {
                throw new IllegalArgumentException(type + " required fields must be allowed fields");
            }
        }
    }

    public static final class CommandContractViolation extends IllegalArgumentException {
        private final DataAuthority.RejectionReason rejectionReason;

        public CommandContractViolation(DataAuthority.RejectionReason rejectionReason, String message) {
            super(message);
            this.rejectionReason = rejectionReason == null
                ? DataAuthority.RejectionReason.VALIDATION_FAILED
                : rejectionReason;
        }

        public DataAuthority.RejectionReason rejectionReason() {
            return rejectionReason;
        }
    }
}
