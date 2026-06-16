package sh.harold.fulcrum.host.api;

public interface HostAllocationPort {
    HostAllocationClaim allocate(HostAllocationRequest request);
}
