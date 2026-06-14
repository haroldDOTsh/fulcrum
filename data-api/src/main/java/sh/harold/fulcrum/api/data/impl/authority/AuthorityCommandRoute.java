package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;

record AuthorityCommandRoute(
    String domain,
    String commandTopic,
    String eventTopic,
    String stateTopic,
    String partitionKey
) {
    private static final String UNKNOWN = "unknown";

    AuthorityCommandRoute {
        domain = known(domain) ? domain : UNKNOWN;
        commandTopic = known(commandTopic) ? commandTopic : "cmd." + domain;
        eventTopic = known(eventTopic) ? eventTopic : "evt." + domain;
        stateTopic = known(stateTopic) ? stateTopic : "state." + domain;
        partitionKey = known(partitionKey) ? partitionKey : UNKNOWN;
    }

    static AuthorityCommandRoute fromCommand(DataAuthority.AuthorityCommand command) {
        return from(command.type(), command.scope());
    }

    static AuthorityCommandRoute from(DataAuthority.CommandType type, String scope) {
        String domain = switch (type) {
            case GRANT_RANK, REVOKE_RANK -> "player_rank";
            case RECORD_MATCH_START, RECORD_MATCH_END -> "match";
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT, START_SESSION, RENEW_SESSION, END_SESSION ->
                "player_profile";
        };
        return new AuthorityCommandRoute(
            domain,
            "cmd." + domain,
            "evt." + domain,
            "state." + domain,
            partitionKey(type, scope)
        );
    }

    static AuthorityCommandRoute fromPayload(
        Map<?, ?> raw,
        DataAuthority.CommandType fallbackType,
        String fallbackScope
    ) {
        AuthorityCommandRoute fallback = from(fallbackType, fallbackScope);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return new AuthorityCommandRoute(
            string(raw.get("domain"), fallback.domain()),
            string(raw.get("commandTopic"), fallback.commandTopic()),
            string(raw.get("eventTopic"), fallback.eventTopic()),
            string(raw.get("stateTopic"), fallback.stateTopic()),
            string(raw.get("partitionKey"), fallback.partitionKey())
        );
    }

    Map<String, Object> payload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("domain", domain);
        values.put("commandTopic", commandTopic);
        values.put("eventTopic", eventTopic);
        values.put("stateTopic", stateTopic);
        values.put("partitionKey", partitionKey);
        return Map.copyOf(values);
    }

    private static String partitionKey(DataAuthority.CommandType type, String scope) {
        return switch (type) {
            case GRANT_RANK, REVOKE_RANK -> scope != null && scope.startsWith("rank:")
                ? scope
                : "rank:" + scope;
            case RECORD_MATCH_START, RECORD_MATCH_END -> scope;
            case RECORD_PLAYER_LOGIN, RECORD_PLAYER_LOGOUT, START_SESSION, RENEW_SESSION, END_SESSION -> scope;
        };
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
    }
}
