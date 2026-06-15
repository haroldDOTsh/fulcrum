package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Authority topology and substrate fingerprints recorded beside command receipts.
 */
public final class AuthorityTopologyEvidence {
    private static final int EVIDENCE_VERSION = 1;
    private static final String TOPOLOGY_KEY = "topology";

    private AuthorityTopologyEvidence() {
    }

    static Map<String, Object> forCommand(
        DataAuthority.AuthorityCommand command,
        AuthorityCommandRoute route
    ) {
        Objects.requireNonNull(command, "command");
        AuthorityCommandRoute effectiveRoute = route == null ? AuthorityCommandRoute.fromCommand(command) : route;
        return forCommandType(command.type(), effectiveRoute);
    }

    static Map<String, Object> forCommandType(
        DataAuthority.CommandType type,
        AuthorityCommandRoute route
    ) {
        Objects.requireNonNull(type, "type");
        AuthorityCommandRoute effectiveRoute = Objects.requireNonNull(route, "route");
        DataAuthorityCommandContracts.CommandContract contract =
            DataAuthorityCommandContracts.contract(type);
        AuthorityDomainTopology.DomainTopology domainTopology =
            AuthorityDomainTopology.domain(contract.domain());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topologyEvidenceVersion", EVIDENCE_VERSION);
        values.put("schemaVersion", DataAuthority.COMMAND_SCHEMA_VERSION);
        values.put("commandContractFingerprint", DataAuthorityCommandContracts.fingerprint());
        values.put("routeManifestFingerprint", DataAuthorityCommandContracts.routeManifestFingerprint());
        values.put("readContractFingerprint", DataAuthorityReadContracts.fingerprint());
        values.put("authorityDomainTopologyFingerprint", AuthorityDomainTopology.fingerprint());
        values.put("authorityStorePlacementFingerprint", AuthorityStorePlacements.fingerprint());
        values.put("authorityLogTopologyFingerprint", logTopologyFingerprint());
        values.put("domain", contract.domain());
        values.put("authorityService", domainTopology.authorityService());
        values.put("consumerGroup", domainTopology.consumerGroup());
        values.put("authorityPrincipal", domainTopology.authorityPrincipal());
        values.put("partitionCount", domainTopology.partitionCount());
        values.put("route", effectiveRoute.payload());
        values.put("stores", Map.of(
            "commandLog", contract.commandLogStore(),
            "hotProjection", contract.hotProjectionStore(),
            "history", contract.historyStore(),
            "cache", contract.cacheStore()
        ));
        values.put("topics", Map.of(
            "command", effectiveRoute.commandTopic(),
            "response", effectiveRoute.responseTopic(),
            "event", effectiveRoute.eventTopic(),
            "state", effectiveRoute.stateTopic()
        ));
        return Map.copyOf(values);
    }

    /**
     * Top-level command-wire fingerprints that bind a runtime command to this authority topology.
     *
     * @param type command type
     * @param scope aggregate scope
     * @param routePayload command route payload
     * @return fields to stamp onto the command wire payload
     */
    public static Map<String, Object> commandWirePayload(
        DataAuthority.CommandType type,
        String scope,
        Map<?, ?> routePayload
    ) {
        Map<String, Object> topology = forCommandType(
            type,
            AuthorityCommandRoute.fromPayload(routePayload, type, fallbackScope(type, scope))
        );
        return Map.of(
            "readContractFingerprint", topology.get("readContractFingerprint"),
            "authorityDomainTopologyFingerprint", topology.get("authorityDomainTopologyFingerprint"),
            "authorityStorePlacementFingerprint", topology.get("authorityStorePlacementFingerprint"),
            "authorityLogTopologyFingerprint", topology.get("authorityLogTopologyFingerprint")
        );
    }

    /**
     * Validates topology fingerprints on a command wire payload.
     *
     * @param type command type
     * @param scope aggregate scope
     * @param routePayload command route payload
     * @param wire command wire payload
     * @return null when accepted, otherwise a stable rejection message
     */
    public static String commandWireRejection(
        DataAuthority.CommandType type,
        String scope,
        Map<?, ?> routePayload,
        Map<?, ?> wire
    ) {
        Map<String, Object> expected = commandWirePayload(type, scope, routePayload);
        for (String field : java.util.List.of(
            "readContractFingerprint",
            "authorityDomainTopologyFingerprint",
            "authorityStorePlacementFingerprint",
            "authorityLogTopologyFingerprint"
        )) {
            String mismatch = commandWireMismatch(wire, expected, field);
            if (mismatch != null) {
                return mismatch;
            }
        }
        return null;
    }

    static void attach(
        Map<String, Object> evidence,
        DataAuthority.AuthorityCommand command,
        AuthorityCommandRoute route
    ) {
        evidence.put(TOPOLOGY_KEY, forCommand(command, route));
    }

    static void attach(
        Map<String, Object> evidence,
        DataAuthority.CommandType type,
        String scope,
        Map<?, ?> routePayload
    ) {
        AuthorityCommandRoute route = AuthorityCommandRoute.fromPayload(
            routePayload,
            type,
            fallbackScope(type, scope)
        );
        evidence.put(TOPOLOGY_KEY, forCommandType(type, route));
    }

    static String replayMismatch(
        DataAuthority.AuthorityCommand command,
        Map<String, Object> guardEvidence
    ) {
        Map<?, ?> stored = storedTopology(guardEvidence);
        if (stored.isEmpty()) {
            return "Stored command topology evidence is missing";
        }

        Map<String, Object> expected = forCommand(command, AuthorityCommandRoute.fromCommand(command));
        String mismatch = mismatch(stored, expected, "schemaVersion");
        if (mismatch != null) {
            return mismatch;
        }
        for (String field : java.util.List.of(
            "commandContractFingerprint",
            "routeManifestFingerprint",
            "readContractFingerprint",
            "authorityDomainTopologyFingerprint",
            "authorityStorePlacementFingerprint",
            "authorityLogTopologyFingerprint",
            "domain",
            "authorityService",
            "consumerGroup",
            "authorityPrincipal",
            "partitionCount"
        )) {
            mismatch = mismatch(stored, expected, field);
            if (mismatch != null) {
                return mismatch;
            }
        }
        return null;
    }

    static Map<?, ?> storedTopology(Map<String, Object> guardEvidence) {
        if (guardEvidence == null || guardEvidence.isEmpty()) {
            return Map.of();
        }
        Object value = guardEvidence.get(TOPOLOGY_KEY);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    public static String logTopologyFingerprint() {
        StringBuilder material = new StringBuilder()
            .append("authorityLogTopology=v1\n")
            .append("partitionCount=").append(AuthorityLogTopology.DEFAULT_PARTITION_COUNT).append('\n');
        AuthorityLogTopology.policiesByTopic().values().stream()
            .sorted(Comparator.comparing(AuthorityLogTopicPolicy::topic))
            .forEach(policy -> material
                .append(policy.topic()).append('|')
                .append(policy.kind().name()).append('|')
                .append(policy.domain()).append('|')
                .append(policy.partitionCount()).append('|')
                .append(policy.compacted()).append('|')
                .append(policy.retentionClass()).append('|')
                .append(policy.keyRule()).append('|')
                .append(String.join(",", policy.producerPrincipalPatterns())).append('|')
                .append(String.join(",", policy.consumerPrincipalPatterns()))
                .append('\n'));
        return AuthorityCommandFingerprints.hash(material.toString());
    }

    private static String mismatch(Map<?, ?> stored, Map<String, Object> expected, String field) {
        String storedValue = string(stored.get(field));
        String expectedValue = string(expected.get(field));
        if (Objects.equals(storedValue, expectedValue)) {
            return null;
        }
        return "Stored command topology " + field + " no longer matches current authority topology";
    }

    private static String commandWireMismatch(Map<?, ?> wire, Map<String, Object> expected, String field) {
        String storedValue = string(wire == null ? null : wire.get(field));
        String expectedValue = string(expected.get(field));
        if (Objects.equals(storedValue, expectedValue)) {
            return null;
        }
        return "Authority command topology " + field + " mismatch: expected "
            + shortFingerprint(expectedValue) + " but received " + shortFingerprint(storedValue);
    }

    private static String fallbackScope(DataAuthority.CommandType type, String scope) {
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return DataAuthorityCommandContracts.contract(type).aggregateScopePrefix() + "unknown";
    }

    private static String string(Object value) {
        if (value instanceof Number number) {
            long longValue = number.longValue();
            return Double.compare(number.doubleValue(), longValue) == 0
                ? Long.toString(longValue)
                : number.toString();
        }
        return value == null ? "" : value.toString();
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "<missing>";
        }
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }
}
