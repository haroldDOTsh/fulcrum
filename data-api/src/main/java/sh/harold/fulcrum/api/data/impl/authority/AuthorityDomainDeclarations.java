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

    static AuthorityCommandRoute route(CommandDeclaration command) {
        return AuthorityCommandRoute.from(command.type(), command.aggregateScopePrefix() + "{aggregateId}");
    }

    private static Map<String, DomainDeclaration> declarations() {
        Map<String, DomainDeclaration> values = new LinkedHashMap<>();
        put(values, declare("match", List.of(
            command(DataAuthority.CommandType.RECORD_MATCH_END, "match:"),
            command(DataAuthority.CommandType.RECORD_MATCH_START, "match:")
        )));
        put(values, declare("player", List.of(
            command(DataAuthority.CommandType.RECORD_PLAYER_LOGIN, "player:"),
            command(DataAuthority.CommandType.RECORD_PLAYER_LOGOUT, "player:")
        )));
        put(values, declare("rank", List.of(
            command(DataAuthority.CommandType.GRANT_RANK, "rank:player:"),
            command(DataAuthority.CommandType.REVOKE_RANK, "rank:player:")
        )));
        put(values, declare("session", List.of(
            command(DataAuthority.CommandType.END_SESSION, "player:"),
            command(DataAuthority.CommandType.RENEW_SESSION, "player:"),
            command(DataAuthority.CommandType.START_SESSION, "player:")
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

    private static CommandDeclaration command(DataAuthority.CommandType type, String aggregateScopePrefix) {
        return new CommandDeclaration(type, aggregateScopePrefix);
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
                String routeDomain = AuthorityDomainDeclarations.route(command).domain();
                if (!domain.equals(routeDomain)) {
                    throw new IllegalArgumentException(
                        "Command " + command.type() + " routes to " + routeDomain + " not " + domain
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

    record CommandDeclaration(DataAuthority.CommandType type, String aggregateScopePrefix) {
        CommandDeclaration {
            type = Objects.requireNonNull(type, "type");
            aggregateScopePrefix = requireText(aggregateScopePrefix, "aggregateScopePrefix");
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
