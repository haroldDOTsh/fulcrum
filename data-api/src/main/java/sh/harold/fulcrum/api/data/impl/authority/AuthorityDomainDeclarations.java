package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class AuthorityDomainDeclarations {
    private static final Map<String, DomainDeclaration> DECLARATIONS = declarations();

    private AuthorityDomainDeclarations() {
    }

    static Map<String, DomainDeclaration> all() {
        return DECLARATIONS;
    }

    static CommandDeclaration command(DataAuthority.CommandType type) {
        Objects.requireNonNull(type, "type");
        for (DomainDeclaration declaration : DECLARATIONS.values()) {
            for (CommandDeclaration command : declaration.commands()) {
                if (command.type() == type) {
                    return command;
                }
            }
        }
        throw new IllegalArgumentException("No authority command declaration for " + type);
    }

    static AuthorityCommandRoute route(CommandDeclaration command) {
        return route(command, command.aggregateScopePrefix() + "{aggregateId}");
    }

    static AuthorityCommandRoute route(DataAuthority.CommandType type, String scope) {
        return route(command(type), scope);
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
            match(DataAuthority.CommandType.RECORD_MATCH_END),
            match(DataAuthority.CommandType.RECORD_MATCH_START)
        )));
        put(values, declare("player", List.of(
            profile(DataAuthority.CommandType.RECORD_PLAYER_LOGIN),
            profile(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT)
        )));
        put(values, declare("rank", List.of(
            rank(DataAuthority.CommandType.GRANT_RANK),
            rank(DataAuthority.CommandType.REVOKE_RANK)
        )));
        put(values, declare("session", List.of(
            session(DataAuthority.CommandType.END_SESSION),
            session(DataAuthority.CommandType.RENEW_SESSION),
            session(DataAuthority.CommandType.START_SESSION)
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

    private static CommandDeclaration profile(DataAuthority.CommandType type) {
        return new CommandDeclaration(
            type,
            DataAuthority.PlayerProfileCommand.class,
            "player",
            DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE,
            DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED,
            "player:",
            "",
            "playerId",
            "player_profile",
            Set.of("playerId", "username", "timestamp"),
            Set.of(
                "playerId", "username", "timestamp", "online", "currentServer", "currentProxy",
                "lastIp", "lastWorld", "lastLocation", "gamemode", "level", "exp", "health",
                "foodLevel", "playtimeStartField"
            )
        );
    }

    private static CommandDeclaration session(DataAuthority.CommandType type) {
        return new CommandDeclaration(
            type,
            DataAuthority.PlayerSessionCommand.class,
            "session",
            DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE,
            DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED,
            "player:",
            "",
            "playerId",
            "player_profile",
            Set.of("playerId", "username", "timestamp"),
            Set.of(
                "playerId", "username", "sessionId", "timestamp", "online", "currentServer",
                "currentProxy", "lastIp", "protocolVersion", "disconnectReason",
                "lastProxySession", "lastServerSwitch", "playtimeStartField", "clearCurrentServer"
            )
        );
    }

    private static CommandDeclaration rank(DataAuthority.CommandType type) {
        return new CommandDeclaration(
            type,
            DataAuthority.PlayerRankCommand.class,
            "rank",
            DataAuthorityCommandContracts.CommandDeliveryMode.SYNC_INTERACTIVE,
            DataAuthorityCommandContracts.CommandRevisionPolicy.COMPARE_REQUIRED,
            "rank:player:",
            "rank:",
            "playerId",
            "player_rank",
            Set.of("playerId", "primaryRank", "ranks"),
            Set.of("playerId", "primaryRank", "ranks")
        );
    }

    private static CommandDeclaration match(DataAuthority.CommandType type) {
        return new CommandDeclaration(
            type,
            DataAuthority.MatchCommand.class,
            "match",
            DataAuthorityCommandContracts.CommandDeliveryMode.ASYNC_DURABLE,
            DataAuthorityCommandContracts.CommandRevisionPolicy.BLIND_ALLOWED,
            "match:",
            "",
            "matchId",
            "match",
            Set.of("matchId", "familyId", "state", "participants"),
            Set.of(
                "matchId", "familyId", "mapId", "serverId", "slotId", "state",
                "startedAt", "endedAt", "slotMetadata", "variant", "targetWorld", "participants"
            )
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
                .sorted(Comparator.comparing(command -> command.type().name()))
                .toList();
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("commands is required");
            }
            Set<DataAuthority.CommandType> commandTypes = commands.stream()
                .map(CommandDeclaration::type)
                .collect(Collectors.toUnmodifiableSet());
            if (commandTypes.size() != commands.size()) {
                throw new IllegalArgumentException("commands must be unique by type");
            }
            for (CommandDeclaration command : commands) {
                if (!domain.equals(command.domain())) {
                    throw new IllegalArgumentException(
                        "Command " + command.type() + " declares " + command.domain() + " not " + domain
                    );
                }
            }
            commandLogStores = distinctSorted(commandLogStores);
            hotProjectionStores = distinctSorted(hotProjectionStores);
            historyStores = distinctSorted(historyStores);
            cacheStores = distinctSorted(cacheStores);
        }

        List<DataAuthority.CommandType> commandTypes() {
            return commands.stream().map(CommandDeclaration::type).toList();
        }

        List<String> aggregateScopePrefixes() {
            return distinctSorted(commands.stream()
                .map(CommandDeclaration::aggregateScopePrefix)
                .toList());
        }
    }

    record CommandDeclaration(
        DataAuthority.CommandType type,
        Class<? extends DataAuthority.AuthorityCommand> commandClass,
        String domain,
        DataAuthorityCommandContracts.CommandDeliveryMode deliveryMode,
        DataAuthorityCommandContracts.CommandRevisionPolicy revisionPolicy,
        String aggregateScopePrefix,
        String partitionKeyPrefix,
        String aggregateIdField,
        String projectionFamily,
        Set<String> requiredPayloadFields,
        Set<String> allowedPayloadFields
    ) {
        CommandDeclaration {
            type = Objects.requireNonNull(type, "type");
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
                throw new IllegalArgumentException(type + " aggregate id field must be required");
            }
            if (!allowedPayloadFields.containsAll(requiredPayloadFields)) {
                throw new IllegalArgumentException(type + " required fields must be allowed fields");
            }
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
