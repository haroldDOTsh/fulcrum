package sh.harold.fulcrum.api.data.impl.authority;

import sh.harold.fulcrum.api.data.authority.DataAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Executable per-domain authority tier topology derived from domain declarations.
 */
public final class AuthorityDomainTopology {
    private static final Map<String, DomainTopology> TOPOLOGIES = topologies();
    private static final String FINGERPRINT = fingerprint(TOPOLOGIES);

    private AuthorityDomainTopology() {
    }

    public static Map<String, DomainTopology> all() {
        return TOPOLOGIES;
    }

    public static DomainTopology domain(String domain) {
        DomainTopology topology = TOPOLOGIES.get(domain);
        if (topology == null) {
            throw new IllegalArgumentException("No authority domain topology for " + domain);
        }
        return topology;
    }

    public static String fingerprint() {
        return FINGERPRINT;
    }

    private static Map<String, DomainTopology> topologies() {
        Map<String, AuthorityLogTopicPolicy> policiesByTopic = AuthorityLogTopology.policiesByTopic();
        Map<String, DomainTopology> values = new LinkedHashMap<>();
        for (AuthorityDomainDeclarations.DomainDeclaration declaration : AuthorityDomainDeclarations.all().values()
            .stream()
            .sorted(Comparator.comparing(AuthorityDomainDeclarations.DomainDeclaration::domain))
            .toList()) {
            String domain = declaration.domain();
            List<AuthorityCommandRoute> routes = routes(declaration);

            String commandTopic = requireSingleRouteValue(domain, routes, AuthorityCommandRoute::commandTopic,
                "commandTopic");
            String responseTopic = requireSingleRouteValue(domain, routes, AuthorityCommandRoute::responseTopic,
                "responseTopic");
            String eventTopic = requireSingleRouteValue(domain, routes, AuthorityCommandRoute::eventTopic,
                "eventTopic");
            String stateTopic = requireSingleRouteValue(domain, routes, AuthorityCommandRoute::stateTopic,
                "stateTopic");
            int partitionCount = requireConsistentPartitionCount(
                domain,
                policiesByTopic,
                commandTopic,
                responseTopic,
                eventTopic,
                stateTopic
            );

            values.put(domain, new DomainTopology(
                domain,
                declaration.authorityService(),
                declaration.consumerGroup(),
                declaration.authorityPrincipal(),
                partitionCount,
                commandTopic,
                responseTopic,
                eventTopic,
                stateTopic,
                declaration.declarationIds(),
                declaration.aggregateScopePrefixes(),
                declaration.commandLogStores(),
                declaration.hotProjectionStores(),
                declaration.historyStores(),
                declaration.cacheStores()
            ));
        }
        return Map.copyOf(values);
    }

    private static List<AuthorityCommandRoute> routes(
        AuthorityDomainDeclarations.DomainDeclaration declaration
    ) {
        return declaration.commands().stream()
            .map(AuthorityDomainDeclarations::route)
            .toList();
    }

    private static String requireSingleRouteValue(
        String domain,
        List<AuthorityCommandRoute> routes,
        Function<AuthorityCommandRoute, String> extractor,
        String field
    ) {
        List<String> values = distinctSorted(routes.stream().map(extractor).toList());
        if (values.size() != 1) {
            throw new IllegalStateException(
                "Authority domain " + domain + " must declare one " + field + " but had " + values
            );
        }
        return values.get(0);
    }

    private static int requireConsistentPartitionCount(
        String domain,
        Map<String, AuthorityLogTopicPolicy> policiesByTopic,
        String... topics
    ) {
        Integer partitionCount = null;
        for (String topic : topics) {
            AuthorityLogTopicPolicy policy = policiesByTopic.get(topic);
            if (policy == null) {
                throw new IllegalStateException("Authority domain " + domain + " is missing topic policy " + topic);
            }
            if (!domain.equals(policy.domain())) {
                throw new IllegalStateException("Authority topic " + topic + " belongs to " + policy.domain()
                    + " but domain topology expected " + domain);
            }
            if (partitionCount == null) {
                partitionCount = policy.partitionCount();
                continue;
            }
            if (partitionCount != policy.partitionCount()) {
                throw new IllegalStateException("Authority domain " + domain
                    + " topic partition counts must match");
            }
        }
        int declaredPartitionCount = AuthorityDomainDeclarations.all().get(domain).partitionCount();
        if (!Objects.equals(partitionCount, declaredPartitionCount)) {
            throw new IllegalStateException("Authority domain " + domain + " topic partition count "
                + partitionCount + " does not match declared " + declaredPartitionCount);
        }
        return declaredPartitionCount;
    }

    private static String fingerprint(Map<String, DomainTopology> topologies) {
        StringBuilder material = new StringBuilder()
            .append("domainTopologySchemaVersion=").append(DataAuthority.COMMAND_SCHEMA_VERSION).append('\n');
        topologies.values().stream()
            .sorted(Comparator.comparing(DomainTopology::domain))
            .forEach(topology -> material.append(topology.material()).append('\n'));
        return sha256(material.toString());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority domain topology", exception);
        }
    }

    private static List<String> distinctSorted(List<String> values) {
        return values.stream().distinct().sorted().toList();
    }

    private static String joinStrings(List<String> values) {
        return String.join(",", values);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public record DomainTopology(
        String domain,
        String authorityService,
        String consumerGroup,
        String authorityPrincipal,
        int partitionCount,
        String commandTopic,
        String responseTopic,
        String eventTopic,
        String stateTopic,
        List<String> declarationIds,
        List<String> aggregateScopePrefixes,
        List<String> commandLogStores,
        List<String> hotProjectionStores,
        List<String> historyStores,
        List<String> cacheStores
    ) {
        public DomainTopology {
            domain = requireText(domain, "domain");
            authorityService = requireText(authorityService, "authorityService");
            consumerGroup = requireText(consumerGroup, "consumerGroup");
            authorityPrincipal = requireText(authorityPrincipal, "authorityPrincipal");
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            commandTopic = requireText(commandTopic, "commandTopic");
            responseTopic = requireText(responseTopic, "responseTopic");
            eventTopic = requireText(eventTopic, "eventTopic");
            stateTopic = requireText(stateTopic, "stateTopic");
            declarationIds = distinctSorted(Objects.requireNonNull(declarationIds, "declarationIds"));
            aggregateScopePrefixes = distinctSorted(Objects.requireNonNull(aggregateScopePrefixes,
                "aggregateScopePrefixes"));
            commandLogStores = distinctSorted(Objects.requireNonNull(commandLogStores, "commandLogStores"));
            hotProjectionStores = distinctSorted(Objects.requireNonNull(hotProjectionStores, "hotProjectionStores"));
            historyStores = distinctSorted(Objects.requireNonNull(historyStores, "historyStores"));
            cacheStores = distinctSorted(Objects.requireNonNull(cacheStores, "cacheStores"));
            if (declarationIds.isEmpty()) {
                throw new IllegalArgumentException("declarationIds is required");
            }
            if (aggregateScopePrefixes.isEmpty()) {
                throw new IllegalArgumentException("aggregateScopePrefixes is required");
            }
        }

        public List<String> topics() {
            return List.of(commandTopic, responseTopic, eventTopic, stateTopic);
        }

        public List<String> commandTopics() {
            return List.of(commandTopic);
        }

        public List<String> responseTopics() {
            return List.of(responseTopic);
        }

        public List<String> eventTopics() {
            return List.of(eventTopic);
        }

        public List<String> stateTopics() {
            return List.of(stateTopic);
        }

        public List<String> allTopics() {
            return topics();
        }

        public List<String> allStores() {
            List<String> stores = new ArrayList<>();
            stores.addAll(commandLogStores);
            stores.addAll(hotProjectionStores);
            stores.addAll(historyStores);
            stores.addAll(cacheStores);
            return distinctSorted(stores);
        }

        String material() {
            return "domainTopology|" + domain
                + "|authorityService=" + authorityService
                + "|consumerGroup=" + consumerGroup
                + "|authorityPrincipal=" + authorityPrincipal
                + "|partitionCount=" + partitionCount
                + "|topics=" + commandTopic + "," + responseTopic + "," + eventTopic + "," + stateTopic
                + "|declarationIds=" + joinStrings(declarationIds)
                + "|aggregateScopePrefixes=" + joinStrings(aggregateScopePrefixes)
                + "|commandLogStores=" + joinStrings(commandLogStores)
                + "|hotProjectionStores=" + joinStrings(hotProjectionStores)
                + "|historyStores=" + joinStrings(historyStores)
                + "|cacheStores=" + joinStrings(cacheStores);
        }
    }
}
