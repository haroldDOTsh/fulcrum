package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Command manifest metadata derived from authority domain declarations.
 */
public final class AuthorityCommandManifest {
    private static final Map<String, DataAuthorityCommandContracts.CommandContract> CONTRACTS_BY_DECLARATION_ID =
        contractsByDeclarationId();
    private static final String FINGERPRINT = fingerprint(CONTRACTS_BY_DECLARATION_ID);
    private static final String ROUTE_MANIFEST_FINGERPRINT = routeManifestFingerprint(CONTRACTS_BY_DECLARATION_ID);
    private static final Map<String, String> ROUTE_PARTITION_KEY_VECTORS = routePartitionKeyVectors(
        CONTRACTS_BY_DECLARATION_ID);
    private static final Map<String, String> COMMAND_TOPICS_BY_DECLARATION_ID = routeVectors(
        CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::commandTopic
    );
    private static final Map<String, String> RESPONSE_TOPICS_BY_DECLARATION_ID = routeVectors(
        CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::responseTopic
    );
    private static final Map<String, String> EVENT_TOPICS_BY_DECLARATION_ID = routeVectors(
        CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::eventTopic
    );
    private static final Map<String, String> STATE_TOPICS_BY_DECLARATION_ID = routeVectors(
        CONTRACTS_BY_DECLARATION_ID,
        AuthorityCommandRoute::stateTopic
    );
    private static final Set<String> COMMAND_TOPICS = commandTopics(CONTRACTS_BY_DECLARATION_ID);

    private AuthorityCommandManifest() {
    }

    public static Map<String, DataAuthorityCommandContracts.CommandContract> allByDeclarationId() {
        return CONTRACTS_BY_DECLARATION_ID;
    }

    public static DataAuthorityCommandContracts.CommandContract declaration(String declarationId) {
        if (declarationId == null || declarationId.isBlank()) {
            throw new IllegalArgumentException("declarationId is required");
        }
        DataAuthorityCommandContracts.CommandContract contract = CONTRACTS_BY_DECLARATION_ID.get(declarationId);
        if (contract == null) {
            throw new IllegalArgumentException("No authority command declaration for " + declarationId);
        }
        return contract;
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

    public static Map<String, String> commandTopicsByDeclarationId() {
        return COMMAND_TOPICS_BY_DECLARATION_ID;
    }

    public static Map<String, String> responseTopicsByDeclarationId() {
        return RESPONSE_TOPICS_BY_DECLARATION_ID;
    }

    public static Map<String, String> eventTopicsByDeclarationId() {
        return EVENT_TOPICS_BY_DECLARATION_ID;
    }

    public static Map<String, String> stateTopicsByDeclarationId() {
        return STATE_TOPICS_BY_DECLARATION_ID;
    }

    public static Set<String> commandTopics() {
        return COMMAND_TOPICS;
    }

    public static Map<String, String> domainsByDeclarationId() {
        return commandMetadata(DataAuthorityCommandContracts.CommandContract::domain);
    }

    public static Map<String, String> deliveryModesByDeclarationId() {
        return commandMetadata(contract -> contract.deliveryMode().name());
    }

    public static Map<String, String> commandLogStoresByDeclarationId() {
        return commandMetadata(DataAuthorityCommandContracts.CommandContract::commandLogStore);
    }

    public static Map<String, String> hotProjectionStoresByDeclarationId() {
        return commandMetadata(DataAuthorityCommandContracts.CommandContract::hotProjectionStore);
    }

    public static Map<String, String> historyStoresByDeclarationId() {
        return commandMetadata(DataAuthorityCommandContracts.CommandContract::historyStore);
    }

    public static Map<String, String> cacheStoresByDeclarationId() {
        return commandMetadata(DataAuthorityCommandContracts.CommandContract::cacheStore);
    }

    public static Map<String, String> authorityServicesByDeclarationId() {
        return topologyMetadata(AuthorityDomainTopology.DomainTopology::authorityService);
    }

    public static Map<String, String> consumerGroupsByDeclarationId() {
        return topologyMetadata(AuthorityDomainTopology.DomainTopology::consumerGroup);
    }

    public static Map<String, String> authorityPrincipalsByDeclarationId() {
        return topologyMetadata(AuthorityDomainTopology.DomainTopology::authorityPrincipal);
    }

    public static Map<String, String> partitionCountsByDeclarationId() {
        return topologyMetadata(topology -> Integer.toString(topology.partitionCount()));
    }

    private static Map<String, DataAuthorityCommandContracts.CommandContract> contractsByDeclarationId() {
        Map<String, DataAuthorityCommandContracts.CommandContract> values = new LinkedHashMap<>();
        for (AuthorityDomainDeclarations.DomainDeclaration domain : AuthorityDomainDeclarations.all().values()) {
            for (AuthorityDomainDeclarations.CommandDeclaration command : domain.commands()) {
                DataAuthorityCommandContracts.CommandContract previous =
                    values.put(command.declarationId(), new DataAuthorityCommandContracts.CommandContract(
                        command.declarationId(),
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

    private static String singleStore(java.util.List<String> stores, String field) {
        if (stores.size() != 1) {
            throw new IllegalStateException(field + " requires one store but had " + stores);
        }
        return stores.iterator().next();
    }

    private static Map<String, String> commandMetadata(
        Function<DataAuthorityCommandContracts.CommandContract, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        CONTRACTS_BY_DECLARATION_ID.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(entry.getKey(), extractor.apply(entry.getValue())));
        return Map.copyOf(values);
    }

    private static Map<String, String> topologyMetadata(
        Function<AuthorityDomainTopology.DomainTopology, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        CONTRACTS_BY_DECLARATION_ID.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> values.put(
                entry.getKey(),
                extractor.apply(AuthorityDomainTopology.domain(entry.getValue().domain()))
            ));
        return Map.copyOf(values);
    }

    private static String fingerprint(
        Map<String, DataAuthorityCommandContracts.CommandContract> contracts
    ) {
        StringBuilder material = new StringBuilder()
            .append("commandSchemaVersion=").append(DataAuthority.COMMAND_SCHEMA_VERSION).append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> material
                .append(contract.declarationId()).append('|')
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
        return sha256(material.toString(), "authority command manifest");
    }

    private static String routeManifestFingerprint(
        Map<String, DataAuthorityCommandContracts.CommandContract> contracts
    ) {
        StringBuilder material = new StringBuilder()
            .append("routeManifestSchemaVersion=").append(DataAuthority.COMMAND_SCHEMA_VERSION).append('\n');
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(contract.declarationId(),
                    sampleScope);
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
        return sha256(material.toString(), "authority command route manifest");
    }

    private static Map<String, String> routePartitionKeyVectors(
        Map<String, DataAuthorityCommandContracts.CommandContract> contracts
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(contract.declarationId(),
                    sampleScope);
                values.put(contract.declarationId(), sampleScope + "=>" + route.partitionKey());
            });
        return Map.copyOf(values);
    }

    private static Map<String, String> routeVectors(
        Map<String, DataAuthorityCommandContracts.CommandContract> contracts,
        Function<AuthorityCommandRoute, String> extractor
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                AuthorityCommandRoute route = AuthorityCommandRoute.fromDeclarationId(contract.declarationId(),
                    sampleScope);
                values.put(contract.declarationId(), extractor.apply(route));
            });
        return Map.copyOf(values);
    }

    private static Set<String> commandTopics(
        Map<String, DataAuthorityCommandContracts.CommandContract> contracts
    ) {
        Set<String> values = new LinkedHashSet<>();
        contracts.values().stream()
            .sorted((left, right) -> left.declarationId().compareTo(right.declarationId()))
            .forEach(contract -> {
                String sampleScope = contract.aggregateScopePrefix() + "{aggregateId}";
                values.add(AuthorityCommandRoute.fromDeclarationId(contract.declarationId(), sampleScope)
                    .commandTopic());
            });
        return Set.copyOf(values);
    }

    private static String sha256(String value, String subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint " + subject, exception);
        }
    }
}
