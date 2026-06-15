package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic authority contract artifacts derived from executable contracts.
 */
public final class AuthorityContractArtifacts {
    private static final List<String> COMMON_COMMAND_TABLES = List.of(
        "authority_command_ingress_log",
        "authority_commands",
        "authority_aggregate_versions",
        "authority_command_consumer_cursors",
        "authority_partition_epochs",
        "authority_writer_claims",
        "authority_events",
        "authority_state_snapshots",
        "authority_state_changelog",
        "authority_idempotency_conflicts"
    );
    private static final Map<DataAuthority.CommandType, List<String>> COMMAND_DOMAIN_TABLES =
        commandDomainTables();
    private static final Map<DataAuthorityReadContracts.ReadType, List<String>> READ_TABLES =
        readTables();

    private AuthorityContractArtifacts() {
    }

    public static TraceabilityManifest traceabilityManifest() {
        FulcrumSchemaContract schema = FulcrumSchemaContract.loadDefault();
        List<AuthorityStorePlacements.StorePlacement> storePlacements = storePlacements();
        List<AuthorityDomainTopology.DomainTopology> domainTopologies = domainTopologies();
        List<TopicRow> topics = topicRows();
        List<CommandRow> commands = commandRows(schema);
        List<ReadRow> reads = readRows(schema);
        return new TraceabilityManifest(
            schema.version(),
            schema.fingerprint(),
            DataAuthorityCommandContracts.fingerprint(),
            DataAuthorityCommandContracts.routeManifestFingerprint(),
            AuthorityDomainTopology.fingerprint(),
            DataAuthorityReadContracts.fingerprint(),
            AuthorityStorePlacements.fingerprint(),
            storePlacements,
            domainTopologies,
            topics,
            commands,
            reads,
            generatedArtifacts(schema, commands, reads)
        );
    }

    public static String traceabilityMaterial() {
        return traceabilityManifest().material();
    }

    public static String traceabilityFingerprint() {
        return sha256(traceabilityMaterial());
    }

    private static List<TopicRow> topicRows() {
        return AuthorityLogTopology.policiesByTopic().values().stream()
            .sorted(Comparator.comparing(AuthorityLogTopicPolicy::topic))
            .map(policy -> new TopicRow(
                policy.topic(),
                policy.kind(),
                policy.domain(),
                policy.partitionCount(),
                policy.compacted(),
                policy.retentionClass(),
                policy.keyRule(),
                policy.producerPrincipalPatterns(),
                policy.consumerPrincipalPatterns()
            ))
            .toList();
    }

    private static List<AuthorityDomainTopology.DomainTopology> domainTopologies() {
        return AuthorityDomainTopology.all().values().stream()
            .sorted(Comparator.comparing(AuthorityDomainTopology.DomainTopology::domain))
            .toList();
    }

    private static List<AuthorityStorePlacements.StorePlacement> storePlacements() {
        return AuthorityStorePlacements.all().values().stream()
            .sorted(Comparator.comparing(AuthorityStorePlacements.StorePlacement::concern))
            .toList();
    }

    private static List<CommandRow> commandRows(FulcrumSchemaContract schema) {
        Map<String, AuthorityLogTopicPolicy> policiesByTopic = AuthorityLogTopology.policiesByTopic();
        return DataAuthorityCommandContracts.all().values().stream()
            .sorted(Comparator.comparing(contract -> contract.type().name()))
            .map(contract -> commandRow(schema, policiesByTopic, contract))
            .toList();
    }

    private static CommandRow commandRow(
        FulcrumSchemaContract schema,
        Map<String, AuthorityLogTopicPolicy> policiesByTopic,
        DataAuthorityCommandContracts.CommandContract contract
    ) {
        String aggregateScopeTemplate = contract.aggregateScopePrefix() + "{aggregateId}";
        AuthorityCommandRoute route = AuthorityCommandRoute.from(contract.type(), aggregateScopeTemplate);
        AuthorityLogTopicPolicy commandPolicy = requirePolicy(policiesByTopic, route.commandTopic());
        AuthorityLogTopicPolicy statePolicy = requirePolicy(policiesByTopic, route.stateTopic());
        return new CommandRow(
            contract.type(),
            contract.domain(),
            contract.deliveryMode(),
            contract.revisionPolicy(),
            contract.commandClass().getName(),
            aggregateScopeTemplate,
            contract.aggregateIdField(),
            route.commandTopic(),
            route.responseTopic(),
            route.eventTopic(),
            route.stateTopic(),
            route.partitionKey(),
            commandPolicy.partitionCount(),
            commandPolicy.retentionClass(),
            statePolicy.compacted(),
            statePolicy.retentionClass(),
            contract.commandLogStore(),
            contract.hotProjectionStore(),
            contract.historyStore(),
            contract.cacheStore(),
            schemaTables(schema, commandTables(contract.type()))
        );
    }

    private static List<ReadRow> readRows(FulcrumSchemaContract schema) {
        return DataAuthorityReadContracts.all().values().stream()
            .sorted(Comparator.comparing(contract -> contract.type().name()))
            .map(contract -> new ReadRow(
                contract.type(),
                contract.projectionFamily(),
                contract.servingStore(),
                contract.cacheStore(),
                contract.minimumRevisionFloor(),
                contract.defaultCacheMaxAgeMillis(),
                DataAuthorityReadContracts.expectedStateTopics(contract.type()),
                schemaTables(schema, readTables(contract.type()))
            ))
            .toList();
    }

    private static List<GeneratedArtifactRow> generatedArtifacts(
        FulcrumSchemaContract schema,
        List<CommandRow> commands,
        List<ReadRow> reads
    ) {
        List<GeneratedArtifactRow> artifacts = new ArrayList<>();
        for (CommandRow command : commands) {
            artifacts.add(generatedCommandClient(command));
            artifacts.add(generatedCommandSerializer(command));
            artifacts.add(generatedProjectionWriter(command));
        }
        for (ReadRow read : reads) {
            artifacts.add(generatedReadClient(read));
            artifacts.add(generatedSnapshotReader(read));
        }
        schema.tables().values().stream()
            .sorted(Comparator.comparing(FulcrumSchemaContract.TableContract::tableName))
            .map(table -> generatedDdl(schema, table))
            .forEach(artifacts::add);
        return artifacts.stream()
            .sorted(Comparator.comparing(GeneratedArtifactRow::artifactId))
            .toList();
    }

    private static GeneratedArtifactRow generatedCommandClient(CommandRow command) {
        return new GeneratedArtifactRow(
            "typed-client/command/" + command.type(),
            GeneratedArtifactKind.TYPED_CLIENT,
            "command:" + command.type(),
            "generated/authority/client/commands/" + command.type() + ".java",
            DataAuthorityCommandContracts.fingerprint(),
            List.of(
                "command-contract:" + command.type(),
                "route:" + command.commandTopic(),
                "route:" + command.responseTopic()
            ),
            List.of(command.commandTopic(), command.responseTopic()),
            tableNames(command.schemaTables())
        );
    }

    private static GeneratedArtifactRow generatedCommandSerializer(CommandRow command) {
        return new GeneratedArtifactRow(
            "command-serializer/" + command.type(),
            GeneratedArtifactKind.COMMAND_SERIALIZER,
            "command:" + command.type(),
            "generated/authority/serializers/" + command.type() + "Serializer.java",
            DataAuthorityCommandContracts.fingerprint(),
            List.of(
                "command-contract:" + command.type(),
                "command-class:" + command.commandClassName(),
                "aggregate-id-field:" + command.aggregateIdField()
            ),
            List.of(command.commandTopic()),
            tableNames(command.schemaTables())
        );
    }

    private static GeneratedArtifactRow generatedProjectionWriter(CommandRow command) {
        return new GeneratedArtifactRow(
            "projection-writer/" + command.type(),
            GeneratedArtifactKind.PROJECTION_WRITER,
            "command:" + command.type(),
            "generated/authority/projections/" + command.domain() + "/" + command.type() + "ProjectionWriter.java",
            DataAuthorityCommandContracts.fingerprint(),
            List.of(
                "command-contract:" + command.type(),
                "event-topic:" + command.eventTopic(),
                "state-topic:" + command.stateTopic(),
                "history-store:" + command.historyStore(),
                "hot-projection-store:" + command.hotProjectionStore()
            ),
            List.of(command.eventTopic(), command.stateTopic()),
            tableNames(command.schemaTables())
        );
    }

    private static GeneratedArtifactRow generatedReadClient(ReadRow read) {
        return new GeneratedArtifactRow(
            "typed-client/read/" + read.type(),
            GeneratedArtifactKind.TYPED_CLIENT,
            "read:" + read.type(),
            "generated/authority/client/reads/" + read.type() + ".java",
            DataAuthorityReadContracts.fingerprint(),
            List.of(
                "read-contract:" + read.type(),
                "projection-family:" + read.projectionFamily(),
                "serving-store:" + read.servingStore()
            ),
            read.expectedStateTopics(),
            tableNames(read.schemaTables())
        );
    }

    private static GeneratedArtifactRow generatedSnapshotReader(ReadRow read) {
        return new GeneratedArtifactRow(
            "snapshot-reader/" + read.type(),
            GeneratedArtifactKind.SNAPSHOT_READER,
            "read:" + read.type(),
            "generated/authority/readers/" + read.type() + "SnapshotReader.java",
            DataAuthorityReadContracts.fingerprint(),
            List.of(
                "read-contract:" + read.type(),
                "projection-family:" + read.projectionFamily(),
                "cache-store:" + read.cacheStore(),
                "minimum-revision-floor:" + read.minimumRevisionFloor()
            ),
            read.expectedStateTopics(),
            tableNames(read.schemaTables())
        );
    }

    private static GeneratedArtifactRow generatedDdl(
        FulcrumSchemaContract schema,
        FulcrumSchemaContract.TableContract table
    ) {
        return new GeneratedArtifactRow(
            "ddl/" + table.tableName(),
            GeneratedArtifactKind.DDL,
            "schema-table:" + table.tableName(),
            "data-api/src/main/resources/" + table.createdBy() + "#" + table.tableName(),
            schema.fingerprint(),
            List.of(
                "schema-version:" + schema.version(),
                "ddl-owner:" + table.ddlOwner(),
                "data-owner:" + table.dataOwner(),
                "created-by:" + table.createdBy()
            ),
            List.of(),
            List.of(table.tableName())
        );
    }

    private static AuthorityLogTopicPolicy requirePolicy(
        Map<String, AuthorityLogTopicPolicy> policiesByTopic,
        String topic
    ) {
        AuthorityLogTopicPolicy policy = policiesByTopic.get(topic);
        if (policy == null) {
            throw new IllegalStateException("Missing authority log topic policy for " + topic);
        }
        return policy;
    }

    private static List<String> commandTables(DataAuthority.CommandType type) {
        LinkedHashSet<String> tables = new LinkedHashSet<>(COMMON_COMMAND_TABLES);
        List<String> domainTables = COMMAND_DOMAIN_TABLES.get(type);
        if (domainTables == null) {
            throw new IllegalArgumentException("No schema table mapping for command " + type);
        }
        tables.addAll(domainTables);
        return List.copyOf(tables);
    }

    private static List<String> readTables(DataAuthorityReadContracts.ReadType type) {
        List<String> tables = READ_TABLES.get(type);
        if (tables == null) {
            throw new IllegalArgumentException("No schema table mapping for read " + type);
        }
        return tables;
    }

    private static List<SchemaTableRef> schemaTables(FulcrumSchemaContract schema, List<String> tableNames) {
        return tableNames.stream()
            .map(schema::table)
            .map(SchemaTableRef::from)
            .toList();
    }

    private static Map<DataAuthority.CommandType, List<String>> commandDomainTables() {
        Map<DataAuthority.CommandType, List<String>> values = new LinkedHashMap<>();
        values.put(DataAuthority.CommandType.RECORD_PLAYER_LOGIN, List.of());
        values.put(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT, List.of());
        values.put(DataAuthority.CommandType.START_SESSION, List.of("player_sessions"));
        values.put(DataAuthority.CommandType.RENEW_SESSION, List.of("player_sessions"));
        values.put(DataAuthority.CommandType.END_SESSION, List.of("player_sessions"));
        values.put(DataAuthority.CommandType.GRANT_RANK, List.of("player_rank_audit"));
        values.put(DataAuthority.CommandType.REVOKE_RANK, List.of("player_rank_audit"));
        values.put(DataAuthority.CommandType.RECORD_MATCH_START, List.of());
        values.put(DataAuthority.CommandType.RECORD_MATCH_END,
            List.of("match_records", "match_participant_stats"));
        if (!values.keySet().containsAll(AuthorityDomainDeclarations.commandTypes())) {
            throw new IllegalStateException("Command table mappings must cover declared commands");
        }
        return Map.copyOf(values);
    }

    private static Map<DataAuthorityReadContracts.ReadType, List<String>> readTables() {
        EnumMap<DataAuthorityReadContracts.ReadType, List<String>> values =
            new EnumMap<>(DataAuthorityReadContracts.ReadType.class);
        values.put(DataAuthorityReadContracts.ReadType.PLAYER_PROFILE,
            List.of("player_profiles", "authority_state_snapshots"));
        values.put(DataAuthorityReadContracts.ReadType.PLAYER_RANK,
            List.of("authority_state_snapshots"));
        return Map.copyOf(values);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority contract artifact", exception);
        }
    }

    private static String joinStrings(List<String> values) {
        return String.join(",", values);
    }

    private static String joinTables(List<SchemaTableRef> tables) {
        List<String> values = new ArrayList<>();
        for (SchemaTableRef table : tables) {
            values.add(table.material());
        }
        return String.join(";", values);
    }

    private static List<String> tableNames(List<SchemaTableRef> tables) {
        return tables.stream()
            .map(SchemaTableRef::tableName)
            .sorted()
            .toList();
    }

    public record TraceabilityManifest(
        int schemaVersion,
        String schemaFingerprint,
        String commandContractFingerprint,
        String routeManifestFingerprint,
        String domainTopologyFingerprint,
        String readContractFingerprint,
        String storePlacementFingerprint,
        List<AuthorityStorePlacements.StorePlacement> storePlacements,
        List<AuthorityDomainTopology.DomainTopology> domainTopologies,
        List<TopicRow> topics,
        List<CommandRow> commands,
        List<ReadRow> reads,
        List<GeneratedArtifactRow> generatedArtifacts
    ) {
        public TraceabilityManifest {
            schemaFingerprint = requireText(schemaFingerprint, "schemaFingerprint");
            commandContractFingerprint = requireText(commandContractFingerprint, "commandContractFingerprint");
            routeManifestFingerprint = requireText(routeManifestFingerprint, "routeManifestFingerprint");
            domainTopologyFingerprint = requireText(domainTopologyFingerprint, "domainTopologyFingerprint");
            readContractFingerprint = requireText(readContractFingerprint, "readContractFingerprint");
            storePlacementFingerprint = requireText(storePlacementFingerprint, "storePlacementFingerprint");
            storePlacements = List.copyOf(Objects.requireNonNull(storePlacements, "storePlacements"));
            domainTopologies = List.copyOf(Objects.requireNonNull(domainTopologies, "domainTopologies"));
            topics = List.copyOf(Objects.requireNonNull(topics, "topics"));
            commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
            reads = List.copyOf(Objects.requireNonNull(reads, "reads"));
            generatedArtifacts = List.copyOf(Objects.requireNonNull(generatedArtifacts, "generatedArtifacts"));
        }

        public String material() {
            StringBuilder material = new StringBuilder()
                .append("authorityContractArtifact=traceability-v3\n")
                .append("schemaVersion=").append(schemaVersion).append('\n')
                .append("schemaFingerprint=").append(schemaFingerprint).append('\n')
                .append("commandContractFingerprint=").append(commandContractFingerprint).append('\n')
                .append("routeManifestFingerprint=").append(routeManifestFingerprint).append('\n')
                .append("domainTopologyFingerprint=").append(domainTopologyFingerprint).append('\n')
                .append("readContractFingerprint=").append(readContractFingerprint).append('\n')
                .append("storePlacementFingerprint=").append(storePlacementFingerprint).append('\n');
            storePlacements.stream()
                .sorted(Comparator.comparing(AuthorityStorePlacements.StorePlacement::concern))
                .forEach(placement -> material.append(placement.material()).append('\n'));
            domainTopologies.stream()
                .sorted(Comparator.comparing(AuthorityDomainTopology.DomainTopology::domain))
                .forEach(topology -> material.append(topology.material()).append('\n'));
            topics.stream()
                .sorted(Comparator.comparing(TopicRow::topic))
                .forEach(topic -> material.append(topic.material()).append('\n'));
            commands.stream()
                .sorted(Comparator.comparing(row -> row.type().name()))
                .forEach(command -> material.append(command.material()).append('\n'));
            reads.stream()
                .sorted(Comparator.comparing(row -> row.type().name()))
                .forEach(read -> material.append(read.material()).append('\n'));
            generatedArtifacts.stream()
                .sorted(Comparator.comparing(GeneratedArtifactRow::artifactId))
                .forEach(artifact -> material.append(artifact.material()).append('\n'));
            return material.toString();
        }

        public String fingerprint() {
            return sha256(material());
        }
    }

    public enum GeneratedArtifactKind {
        TYPED_CLIENT,
        COMMAND_SERIALIZER,
        PROJECTION_WRITER,
        SNAPSHOT_READER,
        DDL
    }

    public record GeneratedArtifactRow(
        String artifactId,
        GeneratedArtifactKind kind,
        String contractRef,
        String outputPath,
        String sourceFingerprint,
        List<String> generatedFrom,
        List<String> topics,
        List<String> schemaTables
    ) {
        public GeneratedArtifactRow {
            artifactId = requireText(artifactId, "artifactId");
            kind = Objects.requireNonNull(kind, "kind");
            contractRef = requireText(contractRef, "contractRef");
            outputPath = requireText(outputPath, "outputPath");
            sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
            generatedFrom = sorted(generatedFrom);
            topics = sorted(topics);
            schemaTables = sorted(schemaTables);
            if (generatedFrom.isEmpty()) {
                throw new IllegalArgumentException("generatedFrom is required");
            }
            if (kind != GeneratedArtifactKind.DDL && schemaTables.isEmpty()) {
                throw new IllegalArgumentException("schemaTables is required for " + artifactId);
            }
        }

        private String material() {
            return "generatedArtifact|" + artifactId
                + "|kind=" + kind
                + "|contractRef=" + contractRef
                + "|outputPath=" + outputPath
                + "|sourceFingerprint=" + sourceFingerprint
                + "|generatedFrom=" + joinStrings(generatedFrom)
                + "|topics=" + joinStrings(topics)
                + "|schemaTables=" + joinStrings(schemaTables);
        }
    }

    public record TopicRow(
        String topic,
        AuthorityLogTopicKind kind,
        String domain,
        int partitionCount,
        boolean compacted,
        String retentionClass,
        String keyRule,
        List<String> producerPrincipalPatterns,
        List<String> consumerPrincipalPatterns
    ) {
        public TopicRow {
            topic = requireText(topic, "topic");
            kind = Objects.requireNonNull(kind, "kind");
            domain = requireText(domain, "domain");
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            retentionClass = requireText(retentionClass, "retentionClass");
            keyRule = requireText(keyRule, "keyRule");
            producerPrincipalPatterns = sorted(producerPrincipalPatterns);
            consumerPrincipalPatterns = sorted(consumerPrincipalPatterns);
            if (producerPrincipalPatterns.isEmpty()) {
                throw new IllegalArgumentException("producerPrincipalPatterns is required");
            }
            if (consumerPrincipalPatterns.isEmpty()) {
                throw new IllegalArgumentException("consumerPrincipalPatterns is required");
            }
        }

        private String material() {
            return "topic|" + topic
                + "|kind=" + kind
                + "|domain=" + domain
                + "|partitionCount=" + partitionCount
                + "|compacted=" + compacted
                + "|retentionClass=" + retentionClass
                + "|keyRule=" + keyRule
                + "|producerPrincipalPatterns=" + joinStrings(producerPrincipalPatterns)
                + "|consumerPrincipalPatterns=" + joinStrings(consumerPrincipalPatterns);
        }
    }

    public record CommandRow(
        DataAuthority.CommandType type,
        String domain,
        DataAuthorityCommandContracts.CommandDeliveryMode deliveryMode,
        DataAuthorityCommandContracts.CommandRevisionPolicy revisionPolicy,
        String commandClassName,
        String aggregateScopeTemplate,
        String aggregateIdField,
        String commandTopic,
        String responseTopic,
        String eventTopic,
        String stateTopic,
        String partitionKeyTemplate,
        int partitionCount,
        String commandRetentionClass,
        boolean stateCompacted,
        String stateRetentionClass,
        String commandLogStore,
        String hotProjectionStore,
        String historyStore,
        String cacheStore,
        List<SchemaTableRef> schemaTables
    ) {
        public CommandRow {
            type = Objects.requireNonNull(type, "type");
            domain = requireText(domain, "domain");
            deliveryMode = Objects.requireNonNull(deliveryMode, "deliveryMode");
            revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
            commandClassName = requireText(commandClassName, "commandClassName");
            aggregateScopeTemplate = requireText(aggregateScopeTemplate, "aggregateScopeTemplate");
            aggregateIdField = requireText(aggregateIdField, "aggregateIdField");
            commandTopic = requireText(commandTopic, "commandTopic");
            responseTopic = requireText(responseTopic, "responseTopic");
            eventTopic = requireText(eventTopic, "eventTopic");
            stateTopic = requireText(stateTopic, "stateTopic");
            partitionKeyTemplate = requireText(partitionKeyTemplate, "partitionKeyTemplate");
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            commandRetentionClass = requireText(commandRetentionClass, "commandRetentionClass");
            stateRetentionClass = requireText(stateRetentionClass, "stateRetentionClass");
            commandLogStore = requireText(commandLogStore, "commandLogStore");
            hotProjectionStore = requireText(hotProjectionStore, "hotProjectionStore");
            historyStore = requireText(historyStore, "historyStore");
            cacheStore = requireText(cacheStore, "cacheStore");
            schemaTables = List.copyOf(Objects.requireNonNull(schemaTables, "schemaTables"));
            if (schemaTables.isEmpty()) {
                throw new IllegalArgumentException("schemaTables is required");
            }
        }

        private String material() {
            return "command|" + type
                + "|domain=" + domain
                + "|deliveryMode=" + deliveryMode
                + "|revisionPolicy=" + revisionPolicy
                + "|commandClass=" + commandClassName
                + "|aggregateScopeTemplate=" + aggregateScopeTemplate
                + "|aggregateIdField=" + aggregateIdField
                + "|topics=" + commandTopic + "," + responseTopic + "," + eventTopic + "," + stateTopic
                + "|partitionKeyTemplate=" + partitionKeyTemplate
                + "|partitionCount=" + partitionCount
                + "|commandRetentionClass=" + commandRetentionClass
                + "|stateCompacted=" + stateCompacted
                + "|stateRetentionClass=" + stateRetentionClass
                + "|stores=" + commandLogStore + "," + hotProjectionStore + "," + historyStore + "," + cacheStore
                + "|schemaTables=" + joinTables(schemaTables);
        }
    }

    public record ReadRow(
        DataAuthorityReadContracts.ReadType type,
        String projectionFamily,
        String servingStore,
        String cacheStore,
        long minimumRevisionFloor,
        long defaultCacheMaxAgeMillis,
        List<String> expectedStateTopics,
        List<SchemaTableRef> schemaTables
    ) {
        public ReadRow {
            type = Objects.requireNonNull(type, "type");
            projectionFamily = requireText(projectionFamily, "projectionFamily");
            servingStore = requireText(servingStore, "servingStore");
            cacheStore = requireText(cacheStore, "cacheStore");
            minimumRevisionFloor = Math.max(0L, minimumRevisionFloor);
            defaultCacheMaxAgeMillis = defaultCacheMaxAgeMillis < 0L ? -1L : defaultCacheMaxAgeMillis;
            expectedStateTopics = List.copyOf(Objects.requireNonNull(expectedStateTopics, "expectedStateTopics"));
            schemaTables = List.copyOf(Objects.requireNonNull(schemaTables, "schemaTables"));
            if (expectedStateTopics.isEmpty()) {
                throw new IllegalArgumentException("expectedStateTopics is required");
            }
            if (schemaTables.isEmpty()) {
                throw new IllegalArgumentException("schemaTables is required");
            }
        }

        private String material() {
            return "read|" + type
                + "|projectionFamily=" + projectionFamily
                + "|servingStore=" + servingStore
                + "|cacheStore=" + cacheStore
                + "|minimumRevisionFloor=" + minimumRevisionFloor
                + "|defaultCacheMaxAgeMillis=" + defaultCacheMaxAgeMillis
                + "|expectedStateTopics=" + joinStrings(expectedStateTopics)
                + "|schemaTables=" + joinTables(schemaTables);
        }
    }

    public record SchemaTableRef(
        String tableName,
        String ddlOwner,
        String dataOwner,
        String createdBy,
        List<String> readers,
        List<String> writers
    ) {
        public SchemaTableRef {
            tableName = requireText(tableName, "tableName");
            ddlOwner = requireText(ddlOwner, "ddlOwner");
            dataOwner = requireText(dataOwner, "dataOwner");
            createdBy = requireText(createdBy, "createdBy");
            readers = sorted(readers);
            writers = sorted(writers);
        }

        private static SchemaTableRef from(FulcrumSchemaContract.TableContract table) {
            return new SchemaTableRef(
                table.tableName(),
                table.ddlOwner(),
                table.dataOwner(),
                table.createdBy(),
                table.readers().stream().toList(),
                table.writers().stream().toList()
            );
        }

        private String material() {
            return tableName
                + "[ddlOwner=" + ddlOwner
                + ",owner=" + dataOwner
                + ",createdBy=" + createdBy
                + ",readers=" + joinStrings(readers)
                + ",writers=" + joinStrings(writers)
                + "]";
        }
    }

    private static List<String> sorted(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().sorted().toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
