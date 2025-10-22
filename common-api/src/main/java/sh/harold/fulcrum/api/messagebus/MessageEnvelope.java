package sh.harold.fulcrum.api.messagebus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Represents a message envelope containing metadata and payload.
 * This class encapsulates all the information needed for message routing and processing.
 */
public record MessageEnvelope(String type, String senderId, String targetId, UUID correlationId, long timestamp,
                              int version, JsonNode payload) {

    /**
     * Creates a new message envelope.
     *
     * @param type          the message type identifier
     * @param senderId      the sender server identifier
     * @param targetId      the target server identifier (null for broadcasts)
     * @param correlationId unique identifier for message correlation
     * @param timestamp     the message creation timestamp
     * @param version       the message format version
     * @param payload       the message payload as JSON
     */
    @JsonCreator
    public MessageEnvelope(@JsonProperty("type") String type,
                           @JsonProperty("senderId") String senderId,
                           @JsonProperty("targetId") String targetId,
                           @JsonProperty("correlationId") UUID correlationId,
                           @JsonProperty("timestamp") long timestamp,
                           @JsonProperty("version") int version,
                           @JsonProperty("payload") JsonNode payload) {
        this.type = type;
        this.senderId = senderId;
        this.targetId = targetId;
        this.correlationId = correlationId;
        this.timestamp = timestamp;
        this.version = version;
        this.payload = payload;
    }

    /**
     * Gets the message type identifier.
     *
     * @return the message type
     */
    @Override
    public String type() {
        return type;
    }

    /**
     * Gets the sender server identifier.
     *
     * @return the sender ID
     */
    @Override
    public String senderId() {
        return senderId;
    }

    /**
     * Gets the target server identifier.
     *
     * @return the target ID, or null for broadcasts
     */
    @Override
    public String targetId() {
        return targetId;
    }

    /**
     * Gets the correlation identifier for message tracking.
     *
     * @return the correlation ID
     */
    @Override
    public UUID correlationId() {
        return correlationId;
    }

    /**
     * Gets the message creation timestamp.
     *
     * @return the timestamp in milliseconds since epoch
     */
    @Override
    public long timestamp() {
        return timestamp;
    }

    /**
     * Gets the message format version.
     *
     * @return the version number
     */
    @Override
    public int version() {
        return version;
    }

    /**
     * Gets the message payload as JSON.
     *
     * @return the payload JsonNode
     */
    @Override
    public JsonNode payload() {
        return payload;
    }

    /**
     * Checks if this is a broadcast message.
     *
     * @return true if targetId is null, false otherwise
     */
    @JsonIgnore
    public boolean isBroadcast() {
        return targetId == null;
    }
}