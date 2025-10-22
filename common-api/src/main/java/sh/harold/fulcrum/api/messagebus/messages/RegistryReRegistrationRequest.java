package sh.harold.fulcrum.api.messagebus.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Message sent by the Registry Service on startup to request all servers and proxies to re-register.
 * This ensures the Registry can recover server state after a restart.
 */
public record RegistryReRegistrationRequest(long timestamp, String reason, boolean forceReregistration) {
    /**
     * Create a re-registration request
     *
     * @param timestamp           The timestamp of the request
     * @param reason              The reason for requesting re-registration (e.g., "Registry restarted")
     * @param forceReregistration Whether to force re-registration even if already registered
     */
    @JsonCreator
    public RegistryReRegistrationRequest(
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("reason") String reason,
            @JsonProperty("forceReregistration") boolean forceReregistration) {
        this.timestamp = timestamp;
        this.reason = reason;
        this.forceReregistration = forceReregistration;
    }

    /**
     * Default constructor for Registry restart scenario
     */
    public RegistryReRegistrationRequest() {
        this(System.currentTimeMillis(), "Registry Service restarted", true);
    }

    @Override
    @JsonProperty("timestamp")
    public long timestamp() {
        return timestamp;
    }

    @Override
    @JsonProperty("reason")
    public String reason() {
        return reason;
    }

    @Override
    @JsonProperty("forceReregistration")
    public boolean forceReregistration() {
        return forceReregistration;
    }

    @Override
    public String toString() {
        return "RegistryReRegistrationRequest{" +
                "timestamp=" + timestamp +
                ", reason='" + reason + '\'' +
                ", forceReregistration=" + forceReregistration +
                '}';
    }
}