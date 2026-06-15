package sh.harold.fulcrum.api.data.impl.authority;

/**
 * Kafka-shaped authority log topic families.
 */
public enum AuthorityLogTopicKind {
    COMMAND("cmd"),
    RESPONSE("rsp"),
    EVENT("evt"),
    STATE("state");

    private final String prefix;

    AuthorityLogTopicKind(String prefix) {
        this.prefix = prefix;
    }

    String topic(AuthorityCommandRoute route) {
        return switch (this) {
            case COMMAND -> route.commandTopic();
            case RESPONSE -> route.responseTopic();
            case EVENT -> route.eventTopic();
            case STATE -> route.stateTopic();
        };
    }

    String prefix() {
        return prefix;
    }
}
