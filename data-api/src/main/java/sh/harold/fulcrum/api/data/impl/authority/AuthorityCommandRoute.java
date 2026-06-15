package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.LinkedHashMap;
import java.util.Map;

record AuthorityCommandRoute(
    String domain,
    String commandTopic,
    String responseTopic,
    String eventTopic,
    String stateTopic,
    String partitionKey
) {
    private static final String UNKNOWN = "unknown";

    AuthorityCommandRoute(
        String domain,
        String commandTopic,
        String eventTopic,
        String stateTopic,
        String partitionKey
    ) {
        this(domain, commandTopic, null, eventTopic, stateTopic, partitionKey);
    }

    AuthorityCommandRoute {
        domain = known(domain) ? domain : UNKNOWN;
        commandTopic = known(commandTopic) ? commandTopic : "cmd." + domain;
        responseTopic = known(responseTopic) ? responseTopic : "rsp." + domain;
        eventTopic = known(eventTopic) ? eventTopic : "evt." + domain;
        stateTopic = known(stateTopic) ? stateTopic : "state." + domain;
        partitionKey = known(partitionKey) ? partitionKey : UNKNOWN;
    }

    static AuthorityCommandRoute fromCommand(DataAuthority.AuthorityCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        AuthorityCommandManifest.CommandContract contract =
            AuthorityCommandManifest.declaration(command.declarationId());
        return fromDeclarationId(contract.declarationId(), command.scope());
    }

    static AuthorityCommandRoute fromDeclarationId(String declarationId, String scope) {
        return AuthorityDomainDeclarations.route(declarationId, scope);
    }

    static AuthorityCommandRoute fromPayload(
        Map<?, ?> raw,
        String fallbackDeclarationId,
        String fallbackScope
    ) {
        AuthorityCommandRoute fallback = fromDeclarationId(fallbackDeclarationId, fallbackScope);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return new AuthorityCommandRoute(
            string(raw.get("domain"), fallback.domain()),
            string(raw.get("commandTopic"), fallback.commandTopic()),
            string(raw.get("responseTopic"), fallback.responseTopic()),
            string(raw.get("eventTopic"), fallback.eventTopic()),
            string(raw.get("stateTopic"), fallback.stateTopic()),
            string(raw.get("partitionKey"), fallback.partitionKey())
        );
    }

    Map<String, Object> payload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("domain", domain);
        values.put("commandTopic", commandTopic);
        values.put("responseTopic", responseTopic);
        values.put("eventTopic", eventTopic);
        values.put("stateTopic", stateTopic);
        values.put("partitionKey", partitionKey);
        return Map.copyOf(values);
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static boolean known(String value) {
        return value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value);
    }
}
