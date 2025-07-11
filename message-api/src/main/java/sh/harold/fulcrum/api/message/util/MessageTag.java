package sh.harold.fulcrum.api.message.util;

public class MessageTag {
    private final String key;
    private final String value;

    public MessageTag(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}