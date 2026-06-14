package sh.harold.fulcrum.registry.server;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeAuthorityDeliveryManifest;
import sh.harold.fulcrum.api.messagebus.messages.RuntimeDataAuthorityAttestation;
import sh.harold.fulcrum.api.messagebus.messages.SlotStatusUpdateMessage;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

/**
 * Data class for registered backend servers.
 */
public class RegisteredServerData {
    
    public enum Status {
        STARTING,
        AVAILABLE,
        UNAVAILABLE,
        RUNNING,
        STOPPING,
        EVACUATING,
        DEAD
    }
    
    private final String serverId;
    private final String tempId;
    private final String serverType;
    private final String address;
    private final int port;
    private final int maxCapacity;
    
    private String role = "default";
    private Status status = Status.STARTING;
    private long lastHeartbeat;
    private int playerCount = 0;
    private double tps = 20.0;
    private double memoryUsage = 0.0;
    private double cpuUsage = 0.0;
    private RuntimeDataAuthorityAttestation dataAuthorityAttestation;
    private RuntimeAuthorityDeliveryManifest authorityDeliveryManifest;

    private final Map<String, LogicalSlotRecord> slots = new ConcurrentHashMap<>();
    private final Map<String, Integer> slotFamilyCapacities = new ConcurrentHashMap<>();
    private final Map<String, Integer> localFamilyReservations = new ConcurrentHashMap<>();
    
    public RegisteredServerData(String serverId, String tempId, String serverType, 
                                String address, int port, int maxCapacity) {
        this.serverId = serverId;
        this.tempId = tempId;
        this.serverType = serverType;
        this.address = address;
        this.port = port;
        this.maxCapacity = maxCapacity;
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    // Getters
    public String getServerId() {
        return serverId;
    }
    
    public String getTempId() {
        return tempId;
    }
    
    public String getServerType() {
        return serverType;
    }
    
    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    public int getMaxCapacity() {
        return maxCapacity;
    }
    
    
    public String getRole() {
        return role;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public int getPlayerCount() {
        return playerCount;
    }
    
    public double getTps() {
        return tps;
    }
    
    public double getMemoryUsage() {
        return memoryUsage;
    }
    
    public double getCpuUsage() {
        return cpuUsage;
    }

    public RuntimeDataAuthorityAttestation getDataAuthorityAttestation() {
        return dataAuthorityAttestation;
    }

    public RuntimeAuthorityDeliveryManifest getAuthorityDeliveryManifest() {
        return authorityDeliveryManifest;
    }

    public Collection<LogicalSlotRecord> getSlots() {
        return Collections.unmodifiableCollection(slots.values());
    }

    public LogicalSlotRecord getSlot(String slotSuffix) {
        return slots.get(slotSuffix);
    }

    public LogicalSlotRecord applySlotUpdate(SlotStatusUpdateMessage update) {
        LogicalSlotRecord slot = slots.computeIfAbsent(update.getSlotSuffix(), suffix ->
            new LogicalSlotRecord(update.getSlotId(), suffix, serverId));
        slot.applyUpdate(update);
        return slot;
    }

    /**
     * Restore a logical slot loaded from durable registry metadata.
     *
     * @param slot slot to restore.
     */
    public void restoreSlot(LogicalSlotRecord slot) {
        if (slot != null && serverId.equals(slot.getServerId())) {
            slots.put(slot.getSlotSuffix(), slot);
        }
    }

    public void clearSlots() {
        slots.clear();
    }

    public Map<String, Integer> getSlotFamilyCapacities() {
        return Collections.unmodifiableMap(slotFamilyCapacities);
    }

    public void updateSlotFamilyCapacities(Map<String, Integer> capacities) {
        slotFamilyCapacities.clear();
        if (capacities != null) {
            capacities.forEach((family, cap) -> slotFamilyCapacities.put(family, Math.max(1, cap)));
        }
        localFamilyReservations.keySet().retainAll(slotFamilyCapacities.keySet());
    }

    public void clearSlotFamilyCapacities() {
        slotFamilyCapacities.clear();
        localFamilyReservations.clear();
    }

    public boolean supportsFamily(String familyId) {
        return slotFamilyCapacities.containsKey(familyId);
    }

    public int getAvailableFamilySlots(String familyId) {
        return Math.max(0, getFamilyCapacity(familyId) - localFamilyReservations.getOrDefault(familyId, 0));
    }

    public int getFamilyCapacity(String familyId) {
        return slotFamilyCapacities.getOrDefault(familyId, 0);
    }

    public boolean reserveFamilySlot(String familyId) {
        AtomicBoolean reserved = new AtomicBoolean(false);
        localFamilyReservations.compute(familyId, (key, current) -> {
            int capacity = getFamilyCapacity(familyId);
            int reservedCount = current != null ? current : 0;
            if (capacity <= 0 || reservedCount >= capacity) {
                return current;
            }
            reserved.set(true);
            return reservedCount + 1;
        });
        return reserved.get();
    }

    public void releaseFamilySlot(String familyId) {
        localFamilyReservations.computeIfPresent(familyId, (key, value) -> value <= 1 ? null : value - 1);
    }

    // Setters
    
    public void setRole(String role) {
        this.role = role;
    }
    
    public void setStatus(Status status) {
        this.status = status;
    }
    
    public void setLastHeartbeat(long lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
    
    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }
    
    public void setTps(double tps) {
        this.tps = tps;
    }
    
    public void setMemoryUsage(double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }
    
    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public void setDataAuthorityAttestation(RuntimeDataAuthorityAttestation dataAuthorityAttestation) {
        this.dataAuthorityAttestation = dataAuthorityAttestation;
    }

    public void setAuthorityDeliveryManifest(RuntimeAuthorityDeliveryManifest authorityDeliveryManifest) {
        this.authorityDeliveryManifest = authorityDeliveryManifest;
    }
    
    /**
     * Check if server is available (running and has capacity)
     */
    public boolean isAvailable() {
        return status == Status.RUNNING && playerCount < maxCapacity;
    }
    
    /**
     * Get time since last heartbeat in milliseconds
     */
    public long getTimeSinceLastHeartbeat() {
        return System.currentTimeMillis() - lastHeartbeat;
    }
    
    @Override
    public String toString() {
        return String.format("Server[id=%s, type=%s, status=%s, players=%d/%d, tps=%.1f]",
            serverId, serverType, status, playerCount, maxCapacity, tps);
    }
}
