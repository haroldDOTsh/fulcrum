package sh.harold.fulcrum.registry.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.allocation.IdAllocator;
import sh.harold.fulcrum.registry.proxy.store.RedisProxyRegistryStore;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.state.RegistrationState;
import sh.harold.fulcrum.registry.state.RegistrationStateMachine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registry for managing proxy servers.
 */
public class ProxyRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRegistry.class);

    // Timeout before an unavailable proxy ID can be recycled (5 minutes)
    private static final long UNAVAILABLE_PROXY_RECYCLE_TIMEOUT_MS = 5 * 60 * 1000;

    private final IdAllocator idAllocator;
    private RedisProxyRegistryStore store;
    private final Map<ProxyIdentifier, RegisteredProxyData> proxies = new ConcurrentHashMap<>();
    private final Map<ProxyIdentifier, RegisteredProxyData> unavailableProxies = new ConcurrentHashMap<>();
    private final Map<ProxyIdentifier, Long> unavailableTimestamps = new ConcurrentHashMap<>();
    private final Map<String, ProxyIdentifier> tempIdToPermId = new ConcurrentHashMap<>();
    private final Map<ProxyIdentifier, Long> registrationTimestamps = new ConcurrentHashMap<>(); // Track when proxies were registered
    private final Map<String, ProxyIdentifier> addressPortToProxyId = new ConcurrentHashMap<>(); // Track proxy by address:port
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService stateMachineExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ProxyRegistry-StateMachine");
        t.setDaemon(true);
        return t;
    });
    private boolean debugMode = false;

    public ProxyRegistry(IdAllocator idAllocator) {
        this(idAllocator, false);
    }

    public ProxyRegistry(IdAllocator idAllocator, boolean debugMode) {
        this.idAllocator = idAllocator;
        this.debugMode = debugMode;
        startCleanupTask();
    }

    public void initialize(RedisManager redisManager) {
        this.store = new RedisProxyRegistryStore(redisManager);

        proxies.clear();
        unavailableProxies.clear();
        unavailableTimestamps.clear();
        tempIdToPermId.clear();
        registrationTimestamps.clear();
        addressPortToProxyId.clear();

        List<RegisteredProxyData> active = store.loadActive(stateMachineExecutor);
        for (RegisteredProxyData proxy : active) {
            proxies.put(proxy.getProxyId(), proxy);
            registrationTimestamps.put(proxy.getProxyId(), System.currentTimeMillis());
            addressPortToProxyId.put(proxy.getAddress() + ":" + proxy.getPort(), proxy.getProxyId());
        }

        Map<RegisteredProxyData, Long> unavailable = store.loadUnavailable(stateMachineExecutor);
        unavailable.forEach((proxy, timestamp) -> {
            unavailableProxies.put(proxy.getProxyId(), proxy);
            unavailableTimestamps.put(proxy.getProxyId(), timestamp);
        });

        store.loadTempMappings().forEach((tempId, proxyIdString) -> {
            try {
                ProxyIdentifier identifier = ProxyIdentifier.parse(proxyIdString);
                tempIdToPermId.put(tempId, identifier);
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Ignoring invalid proxy mapping {} -> {} from Redis", tempId, proxyIdString);
            }
        });

        LOGGER.info("Restored {} active proxies and {} unavailable proxies from Redis", active.size(), unavailable.size());
    }

    /**
     * Set debug mode
     *
     * @param debugMode Enable/disable verbose logging
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /**
     * Register a new proxy with ProxyIdentifier
     *
     * @param proxyId The ProxyIdentifier
     * @param address The proxy address
     * @param port    The proxy port
     * @return The ProxyIdentifier
     */
    public synchronized ProxyIdentifier registerProxy(ProxyIdentifier proxyId, String address, int port) {
        Objects.requireNonNull(proxyId, "ProxyIdentifier cannot be null");
        Objects.requireNonNull(address, "Address cannot be null");

        String addressPortKey = address + ":" + port;

        // Check if this proxy is already registered
        if (proxies.containsKey(proxyId)) {
            if (debugMode) {
                LOGGER.info("Proxy already registered: {} (skipping duplicate registration)",
                        proxyId.getFormattedId());
            }
            return proxyId;
        }

        // Check if a proxy with this address:port already exists
        ProxyIdentifier existingByAddress = addressPortToProxyId.get(addressPortKey);
        if (existingByAddress != null && proxies.containsKey(existingByAddress)) {
            Long registrationTime = registrationTimestamps.get(existingByAddress);
            if (registrationTime != null && (System.currentTimeMillis() - registrationTime) < 30000) {
                LOGGER.info("Proxy at {}:{} was recently registered as {} (within 30s), reusing existing ID",
                        address, port, existingByAddress.getFormattedId());
                return existingByAddress;
            }
        }

        // Check if this proxy was recently unavailable
        RegisteredProxyData unavailableProxy = unavailableProxies.get(proxyId);
        if (unavailableProxy != null) {
            // Reactivate the existing proxy
            unavailableProxies.remove(proxyId);
            unavailableTimestamps.remove(proxyId);
            removeUnavailableProxyState(proxyId);
            unavailableProxy.setStatus(RegisteredProxyData.Status.AVAILABLE);
            unavailableProxy.setLastHeartbeat(System.currentTimeMillis());
            proxies.put(proxyId, unavailableProxy);
            persistActiveProxy(unavailableProxy);

            LOGGER.info("Reactivated previously unavailable proxy: {}",
                    proxyId.getFormattedId());
            return proxyId;
        }

        // Create proxy info with state machine
        RegisteredProxyData proxyInfo = new RegisteredProxyData(proxyId, address, port, stateMachineExecutor);

        // Set initial state to REGISTERING
        boolean transitioned = proxyInfo.transitionTo(RegistrationState.REGISTERING,
                "Starting registration for proxy at " + address + ":" + port);

        if (!transitioned) {
            LOGGER.error("Failed to transition proxy {} to REGISTERING state", proxyId.getFormattedId());
            return null;
        }

        // Register the proxy atomically
        proxies.put(proxyId, proxyInfo);
        registrationTimestamps.put(proxyId, System.currentTimeMillis());
        addressPortToProxyId.put(addressPortKey, proxyId);
        persistActiveProxy(proxyInfo);

        // Transition to REGISTERED state
        proxyInfo.transitionTo(RegistrationState.REGISTERED, "Registration successful");

        // Add state change listener for logging
        proxyInfo.getStateMachine().addStateChangeListener(event -> {
            if (debugMode) {
                LOGGER.debug("State change for proxy {}: {}",
                        event.getProxyIdentifier().getFormattedId(), event);
            }
        });

        LOGGER.info("Registered proxy: {} (address: {}:{}, state: {})",
                proxyId.getFormattedId(), address, port,
                proxyInfo.getRegistrationState());

        return proxyId;
    }

    /**
     * Legacy register method - creates ProxyIdentifier from temp ID
     *
     * @param tempId  The temporary ID from the proxy
     * @param address The proxy address
     * @param port    The proxy port
     * @return The allocated permanent ID
     * @deprecated Use registerProxy(ProxyIdentifier, String, int) instead
     */
    @Deprecated
    public synchronized String registerProxy(String tempId, String address, int port) {
        String addressPortKey = address + ":" + port;

        // First check if a proxy with this address:port already exists
        ProxyIdentifier existingByAddress = addressPortToProxyId.get(addressPortKey);
        if (existingByAddress != null && proxies.containsKey(existingByAddress)) {
            // Check if this was registered recently (within 30 seconds)
            Long registrationTime = registrationTimestamps.get(existingByAddress);
            if (registrationTime != null && (System.currentTimeMillis() - registrationTime) < 30000) {
                LOGGER.info("Proxy at {}:{} was recently registered as {} (within 30s), reusing existing ID",
                        address, port, existingByAddress.getFormattedId());
                // Update the temp ID mapping to point to the existing proxy
                mapTempToProxy(tempId, existingByAddress);
                return existingByAddress.getFormattedId();
            }
        }

        // Check if this proxy is already registered (active) by temp ID
        ProxyIdentifier existingId = tempIdToPermId.get(tempId);
        if (existingId != null) {
            // Check if proxy is still active
            if (proxies.containsKey(existingId)) {
                if (debugMode) {
                    LOGGER.info("Proxy already registered and active: {} -> {} (skipping duplicate registration)",
                            tempId, existingId.getFormattedId());
                }
                return existingId.getFormattedId();
            }

            // Check if this proxy was recently unavailable (prevent ID reuse)
            RegisteredProxyData unavailableProxy = unavailableProxies.get(existingId);
            if (unavailableProxy != null) {
                // Reactivate the existing proxy instead of allocating a new ID
                unavailableProxies.remove(existingId);
                unavailableTimestamps.remove(existingId);
                removeUnavailableProxyState(existingId);
                unavailableProxy.setStatus(RegisteredProxyData.Status.AVAILABLE);
                unavailableProxy.setLastHeartbeat(System.currentTimeMillis());
                // Note: address and port are final, can't update them
                // If proxy reconnects with different address/port, it would need a new registration
                proxies.put(existingId, unavailableProxy);
                persistActiveProxy(unavailableProxy);

                LOGGER.info("Reactivated previously unavailable proxy: {} -> {} (original address: {}:{})",
                        tempId, existingId.getFormattedId(), unavailableProxy.getAddress(), unavailableProxy.getPort());
                if (!unavailableProxy.getAddress().equals(address) || unavailableProxy.getPort() != port) {
                    LOGGER.warn("Proxy {} reconnected with different address/port ({}:{} -> {}:{})",
                            existingId.getFormattedId(), unavailableProxy.getAddress(), unavailableProxy.getPort(), address, port);
                }
                return existingId.getFormattedId();
            }

            // Clean up orphaned mapping
            removeTempMapping(tempId);
            LOGGER.debug("Cleaned up orphaned temp ID mapping for proxy {}", tempId);
        }

        // Allocate NEW contiguous proxy ID (never reuse unavailable IDs)
        String permanentId = idAllocator.allocateProxyId();

        // Create ProxyIdentifier from allocated ID
        ProxyIdentifier proxyId;
        if (permanentId != null && permanentId.startsWith("fulcrum-proxy-")) {
            try {
                String numStr = permanentId.substring("fulcrum-proxy-".length());
                int instanceId = Integer.parseInt(numStr) % 100;
                proxyId = ProxyIdentifier.create(instanceId);
            } catch (NumberFormatException e) {
                // Fall back to instance 0
                proxyId = ProxyIdentifier.create(0);
            }
        } else {
            // Generate new identifier
            proxyId = ProxyIdentifier.create(0);
        }

        // Check for ID collision (extremely rare but possible)
        if (proxies.containsKey(proxyId) || unavailableProxies.containsKey(proxyId)) {
            LOGGER.error("Proxy ID collision detected for {} - this should not happen!", proxyId.getFormattedId());
            throw new IllegalStateException("Proxy ID collision: " + proxyId.getFormattedId());
        }

        // Store mapping
        mapTempToProxy(tempId, proxyId);

        // Register with new identifier
        registerProxy(proxyId, address, port);

        // This is essential log - always show
        LOGGER.info("Registered proxy: {} -> {} (address: {}:{})",
                tempId, proxyId.getFormattedId(), address, port);

        return proxyId.getFormattedId();
    }

    /**
     * Attempt to reactivate a proxy that was previously marked unavailable.
     *
     * @param proxyIdString The proxy identifier string or temp ID
     * @return The reactivated proxy data, or null if not found
     */
    public synchronized RegisteredProxyData reactivateProxy(String proxyIdString) {
        ProxyIdentifier proxyId = resolveProxyIdentifier(proxyIdString);
        if (proxyId == null) {
            return null;
        }

        RegisteredProxyData active = proxies.get(proxyId);
        if (active != null) {
            return active;
        }

        RegisteredProxyData unavailable = unavailableProxies.remove(proxyId);
        if (unavailable == null) {
            return null;
        }

        unavailableTimestamps.remove(proxyId);
        removeUnavailableProxyState(proxyId);
        unavailable.setStatus(RegisteredProxyData.Status.AVAILABLE);
        unavailable.setLastHeartbeat(System.currentTimeMillis());

        RegistrationState previousState = unavailable.getRegistrationState();
        RegistrationState intermediateState = previousState;

        if (RegistrationStateMachine.isValidTransition(previousState, RegistrationState.RE_REGISTERING)) {
            if (unavailable.transitionTo(RegistrationState.RE_REGISTERING, "Heartbeat reactivated proxy")) {
                intermediateState = RegistrationState.RE_REGISTERING;
            }
        }
        if (RegistrationStateMachine.isValidTransition(intermediateState, RegistrationState.REGISTERED)) {
            unavailable.transitionTo(RegistrationState.REGISTERED, "Proxy heartbeat acknowledged");
        }

        proxies.put(proxyId, unavailable);
        registrationTimestamps.put(proxyId, System.currentTimeMillis());
        addressPortToProxyId.put(unavailable.getAddress() + ":" + unavailable.getPort(), proxyId);
        persistActiveProxy(unavailable);

        if (debugMode) {
            LOGGER.info("Reactivated proxy {} from unavailable pool (previous state: {})",
                    proxyId.getFormattedId(), previousState);
        } else {
            LOGGER.info("Reactivated proxy {} from unavailable pool", proxyId.getFormattedId());
        }
        return unavailable;
    }

    /**
     * Deregister a proxy with ProxyIdentifier
     *
     * @param proxyId The ProxyIdentifier to deregister
     */
    public synchronized void deregisterProxy(ProxyIdentifier proxyId) {
        if (proxyId == null) {
            return;
        }

        RegisteredProxyData removed = proxies.remove(proxyId);
        if (removed != null) {
            // Transition to DEREGISTERING state
            removed.transitionTo(RegistrationState.DEREGISTERING, "Proxy deregistration requested");

            // DO NOT release the ID immediately - move to unavailable list
            removed.setStatus(RegisteredProxyData.Status.UNAVAILABLE);
            removeActiveProxyState(removed);
            long timestamp = System.currentTimeMillis();
            unavailableProxies.put(proxyId, removed);
            unavailableTimestamps.put(proxyId, timestamp);
            persistUnavailableProxy(removed, timestamp);

            // Complete deregistration
            removed.transitionTo(RegistrationState.DISCONNECTED, "Proxy disconnected, ID reserved");

            LOGGER.info("Proxy {} marked as unavailable (ID reserved for reconnection, state: {})",
                    proxyId.getFormattedId(), removed.getRegistrationState());
        }
    }

    /**
     * Legacy deregister method
     *
     * @param proxyIdString The proxy ID string to deregister
     * @deprecated Use deregisterProxy(ProxyIdentifier) instead
     */
    @Deprecated
    public synchronized void deregisterProxy(String proxyIdString) {
        if (proxyIdString == null) {
            return;
        }

        // Try to find by temp ID mapping
        ProxyIdentifier proxyId = tempIdToPermId.get(proxyIdString);
        if (proxyId == null) {
            // Try parsing
            try {
                proxyId = ProxyIdentifier.isValid(proxyIdString)
                        ? ProxyIdentifier.parse(proxyIdString)
                        : ProxyIdentifier.fromLegacy(proxyIdString);
            } catch (Exception e) {
                LOGGER.error("Failed to parse proxy ID for deregistration: {}", proxyIdString, e);
                return;
            }
        }

        deregisterProxy(proxyId);
    }

    /**
     * Check if a proxy was recently registered with ProxyIdentifier
     *
     * @param proxyId      The ProxyIdentifier to check
     * @param withinMillis The time window in milliseconds
     * @return true if the proxy was registered within the specified time window
     */
    public boolean wasRecentlyRegistered(ProxyIdentifier proxyId, long withinMillis) {
        Long registrationTime = registrationTimestamps.get(proxyId);
        if (registrationTime != null) {
            return (System.currentTimeMillis() - registrationTime) < withinMillis;
        }
        return false;
    }

    /**
     * Legacy method to check if a proxy was recently registered
     *
     * @param proxyIdString The proxy ID string to check
     * @param withinMillis  The time window in milliseconds
     * @return true if the proxy was registered within the specified time window
     * @deprecated Use wasRecentlyRegistered(ProxyIdentifier, long) instead
     */
    @Deprecated
    public boolean wasRecentlyRegistered(String proxyIdString, long withinMillis) {
        ProxyIdentifier proxyId = tempIdToPermId.get(proxyIdString);
        if (proxyId == null) {
            try {
                proxyId = ProxyIdentifier.isValid(proxyIdString)
                        ? ProxyIdentifier.parse(proxyIdString)
                        : ProxyIdentifier.fromLegacy(proxyIdString);
            } catch (Exception e) {
                return false;
            }
        }
        return wasRecentlyRegistered(proxyId, withinMillis);
    }

    /**
     * Get a proxy by address and port
     *
     * @param address The proxy address
     * @param port    The proxy port
     * @return The ProxyIdentifier if found, null otherwise
     */
    public ProxyIdentifier getProxyByAddress(String address, int port) {
        String key = address + ":" + port;
        return addressPortToProxyId.get(key);
    }

    /**
     * Get a proxy ID string by address and port
     *
     * @param address The proxy address
     * @param port    The proxy port
     * @return The proxy ID string if found, null otherwise
     */
    public String getProxyIdByAddress(String address, int port) {
        ProxyIdentifier proxyId = getProxyByAddress(address, port);
        return proxyId != null ? proxyId.getFormattedId() : null;
    }

    /**
     * Immediately remove a proxy and release its ID (for graceful shutdown)
     *
     * @param proxyIdString The proxy ID string to remove
     * @return true if the proxy was removed, false if not found
     */
    public synchronized boolean removeProxyImmediately(String proxyIdString) {
        // Find the ProxyIdentifier
        ProxyIdentifier proxyId = null;

        // First check if we can find it by direct string match
        for (Map.Entry<ProxyIdentifier, RegisteredProxyData> entry : proxies.entrySet()) {
            if (entry.getKey().getFormattedId().equals(proxyIdString)) {
                proxyId = entry.getKey();
                break;
            }
        }

        // If not found, check temp ID mapping
        if (proxyId == null) {
            proxyId = tempIdToPermId.get(proxyIdString);
        }

        // If still not found, try parsing
        if (proxyId == null) {
            try {
                proxyId = ProxyIdentifier.isValid(proxyIdString)
                        ? ProxyIdentifier.parse(proxyIdString)
                        : ProxyIdentifier.fromLegacy(proxyIdString);
            } catch (Exception e) {
                LOGGER.error("Failed to parse proxy ID for immediate removal: {}", proxyIdString, e);
                return false;
            }
        }

        RegisteredProxyData removed = proxies.remove(proxyId);
        if (removed != null) {
            // Remove all mappings
            final ProxyIdentifier finalProxyId = proxyId;
            removeActiveProxyState(removed);
            removeTempMappingsForProxy(finalProxyId);
            registrationTimestamps.remove(proxyId);
            String addressPortKey = removed.getAddress() + ":" + removed.getPort();
            addressPortToProxyId.remove(addressPortKey);

            // Immediately release the ID for reuse
            idAllocator.releaseProxyIdExplicit(proxyIdString, true);

            LOGGER.info("Proxy {} removed immediately and ID released (graceful shutdown)", proxyIdString);
            return true;
        }

        // Also check unavailable proxies
        removed = unavailableProxies.remove(proxyId);
        if (removed != null) {
            unavailableTimestamps.remove(proxyId);
            final ProxyIdentifier finalProxyId = proxyId;
            removeUnavailableProxyState(proxyId);
            removeTempMappingsForProxy(finalProxyId);
            registrationTimestamps.remove(proxyId);
            String addressPortKey = removed.getAddress() + ":" + removed.getPort();
            addressPortToProxyId.remove(addressPortKey);
            idAllocator.releaseProxyIdExplicit(proxyIdString, true);

            LOGGER.info("Unavailable proxy {} removed immediately and ID released", proxyIdString);
            return true;
        }

        return false;
    }

    /**
     * Permanently remove a proxy and release its ID (after extended timeout)
     *
     * @param proxyId The ProxyIdentifier to permanently remove
     */
    private synchronized void permanentlyRemoveProxy(ProxyIdentifier proxyId) {
        RegisteredProxyData removed = unavailableProxies.remove(proxyId);
        if (removed != null) {
            unavailableTimestamps.remove(proxyId);

            removeUnavailableProxyState(proxyId);

            // Transition to final state
            removed.transitionTo(RegistrationState.UNREGISTERED,
                    "Timeout expired, proxy permanently removed");

            // Shutdown the state machine
            removed.shutdown();

            // Remove all mappings
            removeTempMappingsForProxy(proxyId);
            registrationTimestamps.remove(proxyId);
            String addressPortKey = removed.getAddress() + ":" + removed.getPort();
            addressPortToProxyId.remove(addressPortKey);

            // Explicitly release the ID only after extended timeout
            idAllocator.releaseProxyIdExplicit(proxyId.getFormattedId(), false);

            LOGGER.info("Permanently removed proxy {} after timeout (ID now available for reuse, final state: {})",
                    proxyId.getFormattedId(), removed.getRegistrationState());
        }
    }

    /**
     * Get proxy info by ID
     *
     * @param proxyIdString The proxy ID string
     * @return The proxy info, or null if not found
     */
    public RegisteredProxyData getProxy(String proxyIdString) {
        if (proxyIdString == null) {
            return null;
        }

        // First try direct lookup in case it's already a ProxyIdentifier string representation
        for (Map.Entry<ProxyIdentifier, RegisteredProxyData> entry : proxies.entrySet()) {
            if (entry.getKey().getFormattedId().equals(proxyIdString)) {
                return entry.getValue();
            }
        }

        // Try to find by temp ID mapping
        ProxyIdentifier proxyId = tempIdToPermId.get(proxyIdString);
        if (proxyId != null) {
            return proxies.get(proxyId);
        }

        // Try to parse the string as a ProxyIdentifier
        try {
            proxyId = ProxyIdentifier.isValid(proxyIdString)
                    ? ProxyIdentifier.parse(proxyIdString)
                    : ProxyIdentifier.fromLegacy(proxyIdString);
            return proxies.get(proxyId);
        } catch (Exception e) {
            // Not a valid proxy identifier format
            if (debugMode) {
                LOGGER.debug("Failed to parse proxy ID '{}': {}", proxyIdString, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Get permanent ID from temporary ID
     *
     * @param tempId The temporary ID
     * @return The permanent ID string, or null if not found
     */
    public String getPermanentId(String tempId) {
        ProxyIdentifier proxyId = tempIdToPermId.get(tempId);
        return proxyId != null ? proxyId.getFormattedId() : null;
    }

    /**
     * Get all registered proxies
     *
     * @return Collection of all proxy info
     */
    public Collection<RegisteredProxyData> getAllProxies() {
        return proxies.values();
    }

    /**
     * Update proxy heartbeat
     *
     * @param proxyIdString The proxy ID string
     */
    public void updateHeartbeat(String proxyIdString) {
        RegisteredProxyData proxy = getProxy(proxyIdString);
        if (proxy != null) {
            proxy.setLastHeartbeat(System.currentTimeMillis());
            proxy.setStatus(RegisteredProxyData.Status.AVAILABLE);
            if (debugMode) {
                LOGGER.debug("Updated heartbeat for proxy: {}", proxyIdString);
            }
        } else {
            if (debugMode) {
                LOGGER.warn("Received heartbeat for unregistered proxy: {}", proxyIdString);
            }
        }
    }

    /**
     * Update proxy status
     *
     * @param proxyIdString The proxy ID string
     * @param status        The new status
     */
    public void updateProxyStatus(String proxyIdString, RegisteredProxyData.Status status) {
        RegisteredProxyData proxy = getProxy(proxyIdString);
        if (proxy == null) {
            if (debugMode) {
                LOGGER.debug("Attempted to update status for unknown proxy {}", proxyIdString);
            }
            return;
        }

        RegisteredProxyData.Status oldStatus = proxy.getStatus();
        if (oldStatus == status) {
            return;
        }

        proxy.setStatus(status);

        RegistrationState currentState = proxy.getRegistrationState();
        if (status == RegisteredProxyData.Status.AVAILABLE) {
            if (currentState == RegistrationState.DISCONNECTED) {
                proxy.transitionTo(RegistrationState.RE_REGISTERING, "Proxy heartbeat restored");
                proxy.transitionTo(RegistrationState.REGISTERED, "Proxy marked available");
            } else if (currentState == RegistrationState.RE_REGISTERING) {
                proxy.transitionTo(RegistrationState.REGISTERED, "Proxy marked available");
            }
        } else if (currentState == RegistrationState.REGISTERED) {
            proxy.transitionTo(RegistrationState.DISCONNECTED,
                    "Proxy marked " + status.name().toLowerCase(Locale.ROOT));
        }

        if (debugMode) {
            LOGGER.info("Proxy {} status changed from {} to {} (state: {})",
                    proxyIdString, oldStatus, status, proxy.getRegistrationState());
        }
    }

    /**
     * Check if a proxy is registered
     *
     * @param proxyIdString The proxy ID string
     * @return true if the proxy is registered
     */
    public boolean hasProxy(String proxyIdString) {
        return getProxy(proxyIdString) != null;
    }

    /**
     * Get the total number of registered proxies
     *
     * @return The proxy count
     */
    public int getProxyCount() {
        return proxies.size();
    }

    /**
     * Get the total number of unavailable proxies
     *
     * @return The unavailable proxy count
     */
    public int getUnavailableProxyCount() {
        return unavailableProxies.size();
    }

    /**
     * Force release an unavailable proxy ID (admin action)
     *
     * @param proxyIdString The proxy ID string to force release
     */
    public void forceReleaseProxyId(String proxyIdString) {
        // Try to find by temp ID mapping
        ProxyIdentifier proxyId = resolveProxyIdentifier(proxyIdString);
        if (proxyId == null) {
            LOGGER.error("Failed to resolve proxy ID for force release: {}", proxyIdString);
            return;
        }

        if (unavailableProxies.containsKey(proxyId)) {
            permanentlyRemoveProxy(proxyId);
            LOGGER.warn("Forced release of unavailable proxy ID: {}", proxyId.getFormattedId());
        }
    }

    /**
     * Start the cleanup task for unavailable proxies
     */
    private void startCleanupTask() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            unavailableTimestamps.entrySet().stream()
                    .filter(entry -> now - entry.getValue() > UNAVAILABLE_PROXY_RECYCLE_TIMEOUT_MS)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(this::permanentlyRemoveProxy);
        }, 60, 60, TimeUnit.SECONDS); // Check every minute
    }

    /**
     * Get the count of registered proxies
     */
    public int getRegisteredProxyCount() {
        return proxies.size();
    }

    /**
     * Check if a proxy with the given tempId is registered
     */
    public boolean isProxyRegisteredByTempId(String tempId) {
        ProxyIdentifier proxyId = tempIdToPermId.get(tempId);
        return proxyId != null && proxies.containsKey(proxyId);
    }

    /**
     * Check if a proxy with the given proxyId is registered
     */
    public boolean isProxyRegisteredByProxyId(String proxyIdString) {
        return getProxy(proxyIdString) != null;
    }

    /**
     * Get proxy ID by temporary ID
     *
     * @param tempId The temporary ID
     * @return The proxy ID string if found, null otherwise
     */
    public String getProxyIdByTempId(String tempId) {
        ProxyIdentifier proxyId = tempIdToPermId.get(tempId);
        if (proxyId != null && proxies.containsKey(proxyId)) {
            return proxyId.getFormattedId();
        }
        return null;
    }

    private ProxyIdentifier resolveProxyIdentifier(String proxyIdString) {
        if (proxyIdString == null || proxyIdString.isBlank()) {
            return null;
        }

        // Direct match against active proxies
        for (ProxyIdentifier identifier : proxies.keySet()) {
            if (identifier.getFormattedId().equals(proxyIdString)) {
                return identifier;
            }
        }

        // Direct match against unavailable proxies
        for (ProxyIdentifier identifier : unavailableProxies.keySet()) {
            if (identifier.getFormattedId().equals(proxyIdString)) {
                return identifier;
            }
        }

        ProxyIdentifier fromTemp = tempIdToPermId.get(proxyIdString);
        if (fromTemp != null) {
            return fromTemp;
        }

        try {
            return ProxyIdentifier.isValid(proxyIdString)
                    ? ProxyIdentifier.parse(proxyIdString)
                    : ProxyIdentifier.fromLegacy(proxyIdString);
        } catch (Exception e) {
            if (debugMode) {
                LOGGER.debug("Failed to resolve proxy identifier '{}': {}", proxyIdString, e.getMessage());
            }
            return null;
        }
    }

    private void mapTempToProxy(String tempId, ProxyIdentifier proxyId) {
        if (tempId == null || tempId.isBlank() || proxyId == null) {
            return;
        }
        tempIdToPermId.put(tempId, proxyId);
        if (store != null) {
            store.upsertTempMapping(tempId, proxyId.getFormattedId());
        }
    }

    private void removeTempMapping(String tempId) {
        if (tempId == null || tempId.isBlank()) {
            return;
        }
        tempIdToPermId.remove(tempId);
        if (store != null) {
            store.removeTempMapping(tempId);
        }
    }

    private void removeTempMappingsForProxy(ProxyIdentifier proxyId) {
        if (proxyId == null) {
            return;
        }
        Iterator<Map.Entry<String, ProxyIdentifier>> iterator = tempIdToPermId.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ProxyIdentifier> entry = iterator.next();
            if (proxyId.equals(entry.getValue())) {
                if (store != null) {
                    store.removeTempMapping(entry.getKey());
                }
                iterator.remove();
            }
        }
    }

    private void persistActiveProxy(RegisteredProxyData proxy) {
        if (store != null && proxy != null) {
            store.saveActive(proxy);
        }
    }

    private void persistUnavailableProxy(RegisteredProxyData proxy, long unavailableSince) {
        if (store != null && proxy != null) {
            store.saveUnavailable(proxy, unavailableSince);
        }
    }

    private void removeActiveProxyState(RegisteredProxyData proxy) {
        if (store != null && proxy != null) {
            store.deleteActive(proxy);
        }
    }

    private void removeUnavailableProxyState(ProxyIdentifier proxyId) {
        if (store != null && proxyId != null) {
            store.deleteUnavailable(proxyId.getFormattedId());
        }
    }

    /**
     * Re-register or update an existing proxy
     *
     * @param tempId   The temporary ID from the proxy
     * @param address  The proxy address
     * @param metadata Additional metadata
     * @return The allocated or existing permanent ID
     */
    public synchronized String reRegisterProxy(String tempId, String address, Map<String, Object> metadata) {
        // Extract port from metadata if available
        int port = 0;
        if (metadata != null && metadata.containsKey("port")) {
            Object portObj = metadata.get("port");
            if (portObj instanceof Number) {
                port = ((Number) portObj).intValue();
            }
        }

        // Check if already registered
        String existingId = getProxyIdByTempId(tempId);
        if (existingId != null) {
            LOGGER.info("Proxy {} already registered with ID: {}, updating registration timestamp", tempId, existingId);
            ProxyIdentifier proxyId = tempIdToPermId.get(tempId);
            if (proxyId != null) {
                registrationTimestamps.put(proxyId, System.currentTimeMillis());
            }
            return existingId;
        }

        // Otherwise, register as new
        return registerProxy(tempId, address, port);
    }

    /**
     * Shutdown the cleanup executor and state machines
     */
    public void shutdown() {
        // Shutdown all state machines
        proxies.values().forEach(RegisteredProxyData::shutdown);
        unavailableProxies.values().forEach(RegisteredProxyData::shutdown);

        cleanupExecutor.shutdown();
        stateMachineExecutor.shutdown();

        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
            if (!stateMachineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                stateMachineExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            stateMachineExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
