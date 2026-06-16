package sh.harold.fulcrum.data.store.kafka;

import sh.harold.fulcrum.data.authority.AuthorityEmissionKind;

import java.util.Objects;
import java.util.Optional;

public record KafkaAuthorityEmissionTopics(
        String eventTopic,
        String stateTopic,
        String responseTopic) {
    public KafkaAuthorityEmissionTopics {
        eventTopic = requireTopic(eventTopic, "eventTopic");
        stateTopic = requireTopic(stateTopic, "stateTopic");
        responseTopic = requireTopic(responseTopic, "responseTopic");
    }

    Optional<String> topicFor(AuthorityEmissionKind kind) {
        return switch (kind) {
            case EVENT -> Optional.of(eventTopic);
            case STATE -> Optional.of(stateTopic);
            case RESPONSE -> Optional.of(responseTopic);
            case PROJECTION -> Optional.empty();
            case CACHE_WRITE -> Optional.empty();
        };
    }

    private static String requireTopic(String value, String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
