package sh.harold.fulcrum.api.messagebus.lifecycle;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents the identity of a service in the Fulcrum ecosystem.
 * This is a standardized way to identify any service (backend server, proxy, registry service, etc.)
 */
public class ServiceIdentity {

    private final String tempId;
    private final ServiceType serviceType;
    private final String role;
    private final UUID instanceUuid;
    private final String address;
    private final int port;
    private final long startTime;
    private String serviceId;

    /**
     * Create a new service identity.
     *
     * @param tempId      Temporary ID used during registration
     * @param serviceType Type of service (SERVER, PROXY, REGISTRY)
     * @param role        Service role (e.g., "game", "lobby", "proxy")
     * @param address     Service address/hostname
     * @param port        Service port
     */
    public ServiceIdentity(String tempId, ServiceType serviceType, String role,
                           String address, int port) {
        this.tempId = Objects.requireNonNull(tempId, "tempId cannot be null");
        this.serviceId = tempId; // Initially use temp ID
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType cannot be null");
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.instanceUuid = UUID.randomUUID();
        this.address = Objects.requireNonNull(address, "address cannot be null");
        this.port = port;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Update the service ID after successful registration.
     *
     * @param permanentId The permanent ID assigned by the registry
     */
    public void updateServiceId(String permanentId) {
        if (permanentId != null && !permanentId.isEmpty()) {
            this.serviceId = permanentId;
        }
    }

    /**
     * Check if this service has been assigned a permanent ID.
     *
     * @return true if the service has a permanent ID
     */
    public boolean hasPermanentId() {
        return !serviceId.equals(tempId);
    }

    /**
     * Get the current service ID (may be temp or permanent).
     */
    public String getServiceId() {
        return serviceId;
    }

    /**
     * Get the temporary ID used during registration.
     */
    public String getTempId() {
        return tempId;
    }

    /**
     * Get the service type.
     */
    public ServiceType getServiceType() {
        return serviceType;
    }

    /**
     * Get the service role.
     */
    public String getRole() {
        return role;
    }

    /**
     * Get the unique instance UUID.
     */
    public UUID getInstanceUuid() {
        return instanceUuid;
    }

    /**
     * Get the service address.
     */
    public String getAddress() {
        return address;
    }

    /**
     * Get the service port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the service start time.
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Get the service uptime in milliseconds.
     */
    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }

    @Override
    public String toString() {
        return String.format("ServiceIdentity[id=%s, type=%s, role=%s, address=%s:%d]",
                serviceId, serviceType, role, address, port);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceIdentity that = (ServiceIdentity) o;
        return Objects.equals(instanceUuid, that.instanceUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceUuid);
    }
}

