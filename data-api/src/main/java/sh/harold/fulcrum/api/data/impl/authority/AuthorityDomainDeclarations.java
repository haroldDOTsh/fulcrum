package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

final class AuthorityDomainDeclarations {
    private static final Map<String, DomainDeclaration> DECLARATIONS = declarations();

    private AuthorityDomainDeclarations() {
    }

    static Map<String, DomainDeclaration> all() {
        return DECLARATIONS;
    }

    static List<String> declarationIds() {
        return DECLARATIONS.values().stream()
            .flatMap(declaration -> declaration.commands().stream())
            .map(CommandDeclaration::declarationId)
            .sorted()
            .toList();
    }

    static CommandDeclaration command(String declarationId) {
        String effectiveDeclarationId = requireText(declarationId, "declarationId");
        for (DomainDeclaration declaration : DECLARATIONS.values()) {
            for (CommandDeclaration command : declaration.commands()) {
                if (command.declarationId().equals(effectiveDeclarationId)) {
                    return command;
                }
            }
        }
        throw new IllegalArgumentException("No authority command declaration for " + effectiveDeclarationId);
    }

    static AuthorityCommandRoute route(CommandDeclaration command) {
        return route(command, command.aggregateScopePrefix() + "{aggregateId}");
    }

    static AuthorityCommandRoute route(String declarationId, String scope) {
        return route(command(declarationId), scope);
    }

    static AuthorityCommandRoute route(CommandDeclaration command, String scope) {
        String domain = command.domain();
        return new AuthorityCommandRoute(
            domain,
            "cmd." + domain,
            "rsp." + domain,
            "evt." + domain,
            "state." + domain,
            partitionKey(command, scope)
        );
    }

    private static Map<String, DomainDeclaration> declarations() {
        Map<String, DomainDeclaration> values = new LinkedHashMap<>();
        put(values, declare("match", List.of(
            match("RECORD_MATCH_END"),
            match("RECORD_MATCH_START")
        )));
        put(values, declare("player", List.of(
            profile("RECORD_PLAYER_LOGIN"),
            profile("RECORD_PLAYER_LOGOUT")
        )));
        put(values, declare("rank", List.of(
            rank("GRANT_RANK"),
            rank("REVOKE_RANK")
        )));
        put(values, declare("session", List.of(
            session("END_SESSION"),
            session("RENEW_SESSION"),
            session("START_SESSION")
        )));
        return Map.copyOf(values);
    }

    private static void put(Map<String, DomainDeclaration> values, DomainDeclaration declaration) {
        values.put(declaration.domain(), declaration);
    }

    private static DomainDeclaration declare(String domain, List<CommandDeclaration> commands) {
        return new DomainDeclaration(
            domain,
            "authority-" + domain,
            "authority-" + domain,
            "authority-" + domain,
            AuthorityCommandLane.DEFAULT_LANE_COUNT,
            commands,
            List.of("kafka"),
            List.of("cassandra"),
            List.of("postgresql"),
            List.of("valkey")
        );
    }

    private static CommandDeclaration profile(String declarationId) {
        return new CommandDeclaration(
            declarationId,
            DataAuthority.PlayerProfileCommand.class,
            "player",
            AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE,
            AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED,
            "player:",
            "",
            "playerId",
            "player_profile",
            Set.of("playerId", "username", "timestamp"),
            Set.of(
                "playerId", "subjectId", "username", "timestamp", "lastIp", "lastWorld", "lastLocation",
                "gamemode", "level", "exp", "health", "foodLevel", "playtimeStartField"
            ),
            AuthorityCommandPayloads::profilePayload,
            AuthorityCommandPayloads::profileCommand
        );
    }

    private static CommandDeclaration session(String declarationId) {
        return new CommandDeclaration(
            declarationId,
            DataAuthority.PlayerSessionCommand.class,
            "session",
            AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE,
            AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED,
            DataAuthority.Subject.SCOPE_PREFIX,
            "",
            "subjectId",
            "presence",
            Set.of("subjectId", "playerId", "username", "timestamp"),
            Set.of(
                "subjectId", "playerId", "username", "sessionId", "timestamp", "online", "currentServer",
                "currentProxy", "lastIp", "protocolVersion", "disconnectReason",
                "lastProxySession", "lastServerSwitch", "playtimeStartField", "clearCurrentServer"
            ),
            AuthorityCommandPayloads::sessionPayload,
            AuthorityCommandPayloads::sessionCommand
        );
    }

    private static CommandDeclaration rank(String declarationId) {
        return new CommandDeclaration(
            declarationId,
            DataAuthority.PlayerRankCommand.class,
            "rank",
            AuthorityCommandManifest.CommandDeliveryMode.SYNC_INTERACTIVE,
            AuthorityCommandManifest.CommandRevisionPolicy.COMPARE_REQUIRED,
            "rank:player:",
            "rank:",
            "playerId",
            "player_rank",
            Set.of("playerId", "primaryRank", "ranks"),
            Set.of("playerId", "primaryRank", "ranks"),
            AuthorityCommandPayloads::rankPayload,
            AuthorityCommandPayloads::rankCommand
        );
    }

    private static CommandDeclaration match(String declarationId) {
        return new CommandDeclaration(
            declarationId,
            DataAuthority.MatchCommand.class,
            "match",
            AuthorityCommandManifest.CommandDeliveryMode.ASYNC_DURABLE,
            AuthorityCommandManifest.CommandRevisionPolicy.BLIND_ALLOWED,
            "match:",
            "",
            "matchId",
            "match",
            Set.of("matchId", "familyId", "state", "participants"),
            Set.of(
                "matchId", "familyId", "mapId", "serverId", "slotId", "state",
                "startedAt", "endedAt", "slotMetadata", "variant", "targetWorld", "participants"
            ),
            AuthorityCommandPayloads::matchPayload,
            AuthorityCommandPayloads::matchCommand
        );
    }

    private static String partitionKey(CommandDeclaration command, String scope) {
        if (scope == null || scope.isBlank()) {
            return "unknown";
        }
        if (!command.partitionKeyPrefix().isBlank() && !scope.startsWith(command.aggregateScopePrefix())) {
            return command.partitionKeyPrefix() + scope;
        }
        return scope;
    }

    record DomainDeclaration(
        String domain,
        String authorityService,
        String consumerGroup,
        String authorityPrincipal,
        int partitionCount,
        List<CommandDeclaration> commands,
        List<String> commandLogStores,
        List<String> hotProjectionStores,
        List<String> historyStores,
        List<String> cacheStores
    ) {
        DomainDeclaration {
            domain = requireText(domain, "domain");
            authorityService = requireText(authorityService, "authorityService");
            consumerGroup = requireText(consumerGroup, "consumerGroup");
            authorityPrincipal = requireText(authorityPrincipal, "authorityPrincipal");
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            commands = commands == null ? List.of() : commands.stream()
                .sorted(Comparator.comparing(CommandDeclaration::declarationId))
                .toList();
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("commands is required");
            }
            Set<String> declarationIds = commands.stream()
                .map(CommandDeclaration::declarationId)
                .collect(Collectors.toUnmodifiableSet());
            if (declarationIds.size() != commands.size()) {
                throw new IllegalArgumentException("commands must be unique by declaration id");
            }
            for (CommandDeclaration command : commands) {
                if (!domain.equals(command.domain())) {
                    throw new IllegalArgumentException(
                        "Command " + command.declarationId() + " declares " + command.domain() + " not " + domain
                    );
                }
            }
            commandLogStores = distinctSorted(commandLogStores);
            hotProjectionStores = distinctSorted(hotProjectionStores);
            historyStores = distinctSorted(historyStores);
            cacheStores = distinctSorted(cacheStores);
        }

        List<String> declarationIds() {
            return commands.stream().map(CommandDeclaration::declarationId).toList();
        }

        List<String> aggregateScopePrefixes() {
            return distinctSorted(commands.stream()
                .map(CommandDeclaration::aggregateScopePrefix)
                .toList());
        }
    }

    record CommandDeclaration(
        String declarationId,
        Class<? extends DataAuthority.AuthorityCommand> commandClass,
        String domain,
        AuthorityCommandManifest.CommandDeliveryMode deliveryMode,
        AuthorityCommandManifest.CommandRevisionPolicy revisionPolicy,
        String aggregateScopePrefix,
        String partitionKeyPrefix,
        String aggregateIdField,
        String projectionFamily,
        Set<String> requiredPayloadFields,
        Set<String> allowedPayloadFields,
        Function<DataAuthority.AuthorityCommand, Map<String, Object>> payloadEncoder,
        BiFunction<DataAuthority.CommandManifest, Map<String, Object>, DataAuthority.AuthorityCommand> payloadDecoder
    ) {
        CommandDeclaration {
            declarationId = requireText(declarationId, "declarationId");
            commandClass = Objects.requireNonNull(commandClass, "commandClass");
            domain = requireText(domain, "domain");
            deliveryMode = Objects.requireNonNull(deliveryMode, "deliveryMode");
            revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
            aggregateScopePrefix = requireText(aggregateScopePrefix, "aggregateScopePrefix");
            partitionKeyPrefix = partitionKeyPrefix == null ? "" : partitionKeyPrefix;
            aggregateIdField = requireText(aggregateIdField, "aggregateIdField");
            projectionFamily = requireText(projectionFamily, "projectionFamily");
            requiredPayloadFields = requiredPayloadFields == null ? Set.of() : Set.copyOf(requiredPayloadFields);
            allowedPayloadFields = allowedPayloadFields == null ? Set.of() : Set.copyOf(allowedPayloadFields);
            if (!requiredPayloadFields.contains(aggregateIdField)) {
                throw new IllegalArgumentException(declarationId + " aggregate id field must be required");
            }
            if (!allowedPayloadFields.containsAll(requiredPayloadFields)) {
                throw new IllegalArgumentException(declarationId + " required fields must be allowed fields");
            }
            payloadEncoder = Objects.requireNonNull(payloadEncoder, "payloadEncoder");
            payloadDecoder = Objects.requireNonNull(payloadDecoder, "payloadDecoder");
        }

        Map<String, Object> payload(DataAuthority.AuthorityCommand command) {
            if (!commandClass.isInstance(command)) {
                throw new IllegalArgumentException(
                    "Command " + declarationId + " must be " + commandClass.getSimpleName()
                );
            }
            return payloadEncoder.apply(command);
        }

        DataAuthority.AuthorityCommand toCommand(
            DataAuthority.CommandManifest manifest,
            Map<String, Object> payload
        ) {
            Objects.requireNonNull(manifest, "manifest");
            if (!declarationId.equals(manifest.declarationId())) {
                throw new IllegalArgumentException(
                    "Command manifest declares " + manifest.declarationId() + " not " + declarationId
                );
            }
            DataAuthority.AuthorityCommand command = payloadDecoder.apply(
                manifest,
                payload == null ? Map.of() : Map.copyOf(payload)
            );
            if (!commandClass.isInstance(command)) {
                throw new IllegalStateException(
                    "Command decoder for " + declarationId + " produced " + command.getClass().getName()
                );
            }
            return command;
        }

    }

    private static List<String> distinctSorted(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
