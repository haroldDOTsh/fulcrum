package sh.harold.fulcrum.messagebus;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope containing message metadata and payload.
 * This is a generic container for all messages passing through the bus.
 */
public class MessageEnvelope {
    
    private final String id;
    private final String messageType;
    private final String sourceServer;
    private final String targetServer; // null for broadcasts
    private final Object payload;
    private final Instant timestamp;
    private final String correlationId; // For request/response correlation
    
    /**
     * Create a new message envelope.
     * 
     * @param messageType The message type identifier
     * @param sourceServer The source server identifier
     * @param targetServer The target server (null for broadcasts)
     * @param payload The message payload
     * @param correlationId Correlation ID for request/response
     */
    public MessageEnvelope(String messageType, String sourceServer, String targetServer, 
                           Object payload, String correlationId) {
        this.id = UUID.randomUUID().toString();
        this.messageType = messageType;
        this.sourceServer = sourceServer;
        this.targetServer = targetServer;
        this.payload = payload;
        this.timestamp = Instant.now();
        this.correlationId = correlationId;
    }
    
    /**
     * Create a broadcast message envelope.
     * 
     * @param messageType The message type identifier
     * @param sourceServer The source server identifier
     * @param payload The message payload
     */
    public MessageEnvelope(String messageType, String sourceServer, Object payload) {
        this(messageType, sourceServer, null, payload, null);
    }
    
    public String getId() {
        return id;
    }
    
    public String getMessageType() {
        return messageType;
    }
    
    public String getSourceServer() {
        return sourceServer;
    }
    
    public String getTargetServer() {
        return targetServer;
    }
    
    public Object getPayload() {
        return payload;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public String getCorrelationId() {
        return correlationId;
    }
    
    public boolean isBroadcast() {
        return targetServer == null;
    }
    
    public boolean isRequest() {
        return correlationId != null && messageType.endsWith(".request");
    }
    
    public boolean isResponse() {
        return correlationId != null && messageType.endsWith(".response");
    }
    
    @Override
    public String toString() {
        return String.format("MessageEnvelope{id='%s', type='%s', source='%s', target='%s', correlationId='%s'}",
                id, messageType, sourceServer, targetServer, correlationId);
    }
}