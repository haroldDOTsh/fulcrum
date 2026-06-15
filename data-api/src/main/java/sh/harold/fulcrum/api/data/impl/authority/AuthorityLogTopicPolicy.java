package sh.harold.fulcrum.api.data.impl.authority;

import java.util.List;
import java.util.Objects;

/**
 * Executable topic policy for one authority log topic.
 */
public record AuthorityLogTopicPolicy(
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
    public AuthorityLogTopicPolicy(
        String topic,
        AuthorityLogTopicKind kind,
        String domain,
        int partitionCount,
        boolean compacted,
        String retentionClass
    ) {
        this(
            topic,
            kind,
            domain,
            partitionCount,
            compacted,
            retentionClass,
            defaultKeyRule(kind),
            defaultProducerPrincipalPatterns(kind, domain),
            defaultConsumerPrincipalPatterns(kind, domain)
        );
    }

    public AuthorityLogTopicPolicy {
        topic = requireText(topic, "topic");
        kind = Objects.requireNonNull(kind, "kind");
        domain = requireText(domain, "domain");
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be positive");
        }
        retentionClass = requireText(retentionClass, "retentionClass");
        keyRule = requireText(keyRule, "keyRule");
        producerPrincipalPatterns = sortedRequired(producerPrincipalPatterns, "producerPrincipalPatterns");
        consumerPrincipalPatterns = sortedRequired(consumerPrincipalPatterns, "consumerPrincipalPatterns");
    }

    private static String defaultKeyRule(AuthorityLogTopicKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case COMMAND, EVENT -> "aggregate-id";
            case RESPONSE -> "aggregate-id-command-result-correlation";
            case STATE -> "aggregate-id-compacted-snapshot";
        };
    }

    private static List<String> defaultProducerPrincipalPatterns(AuthorityLogTopicKind kind, String domain) {
        String authorityPrincipal = "authority-" + requireText(domain, "domain");
        return switch (Objects.requireNonNull(kind, "kind")) {
            case COMMAND -> List.of("node:*");
            case RESPONSE, EVENT, STATE -> List.of(authorityPrincipal);
        };
    }

    private static List<String> defaultConsumerPrincipalPatterns(AuthorityLogTopicKind kind, String domain) {
        String authorityPrincipal = "authority-" + requireText(domain, "domain");
        return switch (Objects.requireNonNull(kind, "kind")) {
            case COMMAND -> List.of(authorityPrincipal);
            case RESPONSE -> List.of("node:*");
            case EVENT -> List.of("ops:*", "projection-worker:*");
            case STATE -> List.of("ops:*", "projection-worker:*", "recovery-worker:*");
        };
    }

    private static List<String> sortedRequired(List<String> values, String field) {
        List<String> normalized = values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, field + " entry"))
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
