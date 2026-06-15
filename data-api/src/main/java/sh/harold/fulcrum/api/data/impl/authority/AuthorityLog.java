package sh.harold.fulcrum.api.data.impl.authority;

import java.util.Map;
import java.util.List;

public interface AuthorityLog {
    AuthorityLogRecord append(
        AuthorityCommandRoute route,
        AuthorityLogTopicKind kind,
        Map<String, Object> payload
    );

    default List<AuthorityLogRecord> records(
        String topic,
        int partition,
        long afterOffset,
        int maxRecords
    ) {
        throw new UnsupportedOperationException("Authority log does not expose partition reads");
    }

    Map<String, AuthorityLogTopicPolicy> policiesByTopic();

    default void validateSchema() {
        Map<String, AuthorityLogTopicPolicy> policies = policiesByTopic();
        for (String requiredTopic : AuthorityLogTopology.policiesByTopic().keySet()) {
            if (!policies.containsKey(requiredTopic)) {
                throw new IllegalStateException("Missing authority log topic policy " + requiredTopic);
            }
        }
    }
}
