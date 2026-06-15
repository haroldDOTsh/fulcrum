package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derived authority log topology for cmd/evt/state/rsp topics.
 */
public final class AuthorityLogTopology {
    public static final int DEFAULT_PARTITION_COUNT = AuthorityCommandLane.DEFAULT_LANE_COUNT;

    private AuthorityLogTopology() {
    }

    public static AuthorityLogTopicPolicy policy(
        AuthorityCommandRoute route,
        AuthorityLogTopicKind kind
    ) {
        return policy(route, kind, DEFAULT_PARTITION_COUNT);
    }

    private static AuthorityLogTopicPolicy policy(
        AuthorityCommandRoute route,
        AuthorityLogTopicKind kind,
        int partitionCount
    ) {
        return new AuthorityLogTopicPolicy(
            kind.topic(route),
            kind,
            route.domain(),
            partitionCount,
            kind == AuthorityLogTopicKind.STATE,
            retentionClass(kind)
        );
    }

    public static Map<String, AuthorityLogTopicPolicy> policiesByTopic() {
        Map<String, AuthorityLogTopicPolicy> values = new LinkedHashMap<>();
        AuthorityDomainDeclarations.all().values().stream()
            .sorted(Comparator.comparing(AuthorityDomainDeclarations.DomainDeclaration::domain))
            .forEach(declaration -> declaration.commands().forEach(command -> {
                AuthorityCommandRoute route = AuthorityDomainDeclarations.route(command);
                for (AuthorityLogTopicKind kind : AuthorityLogTopicKind.values()) {
                    values.putIfAbsent(kind.topic(route), policy(route, kind, declaration.partitionCount()));
                }
            }));
        return Map.copyOf(values);
    }

    public static int partition(AuthorityCommandRoute route) {
        return AuthorityCommandLane.fromRoute(route, DEFAULT_PARTITION_COUNT).lane();
    }

    public static int partition(String domain, String partitionKey) {
        return partition(new AuthorityCommandRoute(
            domain,
            "cmd." + domain,
            "rsp." + domain,
            "evt." + domain,
            "state." + domain,
            partitionKey
        ));
    }

    public static String key(AuthorityCommandRoute route) {
        return route.partitionKey();
    }

    public static AuthorityCommandRoute route(DataAuthority.AuthorityCommand command) {
        return AuthorityCommandRoute.fromCommand(command);
    }

    private static String retentionClass(AuthorityLogTopicKind kind) {
        return switch (kind) {
            case COMMAND -> "retained-days";
            case RESPONSE -> "retained-hours";
            case EVENT -> "retained-weeks";
            case STATE -> "compacted-forever";
        };
    }
}
