package sh.harold.fulcrum.registry.allocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages contiguous ID allocation for servers and proxies.
 * <p>
 * ID formats:
 * - Proxies: fulcrum-proxy-N (where N is contiguous)
 * - Backend servers: mini1, mega2, etc.
 * - Pool slots: mini1A, mini1B, mega2C, etc. (letter indicates slot)
 */
public class IdAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdAllocator.class);

    // Pattern to extract server type and number from IDs
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("^(mini|mega|pool)(\\d+)([A-Z]?)$");
    private static final Pattern PROXY_ID_PATTERN = Pattern.compile("^fulcrum-proxy-(\\d+)$");

    // Track available numbers for reuse (for contiguous allocation)
    private final Map<String, TreeSet<Integer>> availableNumbers = new ConcurrentHashMap<>();

    // Track next numbers to allocate
    private final Map<String, AtomicInteger> nextNumbers = new ConcurrentHashMap<>();

    // Track allocated slot letters per server
    private final Map<String, TreeSet<Character>> allocatedSlots = new ConcurrentHashMap<>();

    // Debug mode flag
    private boolean debugMode = false;

    /**
     * Default constructor
     */
    public IdAllocator() {
        this(false);
    }

    /**
     * Constructor with debug mode
     *
     * @param debugMode Enable verbose logging
     */
    public IdAllocator(boolean debugMode) {
        this.debugMode = debugMode;
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
     * Allocate a new server ID
     *
     * @param serverType The type of server (mini, mega, pool)
     * @return The allocated ID
     */
    public synchronized String allocateServerId(String serverType) {
        serverType = serverType.toLowerCase();

        // Get or create available set for this type
        TreeSet<Integer> available = availableNumbers.computeIfAbsent(serverType, k -> new TreeSet<>());

        int number;
        if (!available.isEmpty()) {
            // Reuse lowest available number
            number = available.pollFirst();
            if (debugMode) {
                LOGGER.debug("Reusing ID number {} for type {}", number, serverType);
            }
        } else {
            // Allocate next sequential number
            AtomicInteger nextNumber = nextNumbers.computeIfAbsent(serverType, k -> new AtomicInteger(1));
            number = nextNumber.getAndIncrement();
            if (debugMode) {
                LOGGER.debug("Allocating new ID number {} for type {}", number, serverType);
            }
        }

        String serverId = serverType + number;
        if (debugMode) {
            LOGGER.info("Allocated server ID: {}", serverId);
        }
        return serverId;
    }

    /**
     * Allocate a pool slot ID (e.g., mini1A, mini1B)
     *
     * @param baseServerId The base server ID (e.g., mini1)
     * @return The allocated slot ID
     */
    public synchronized String allocateSlotId(String baseServerId) {
        TreeSet<Character> slots = allocatedSlots.computeIfAbsent(baseServerId, k -> new TreeSet<>());

        // Find next available letter
        char nextSlot = 'A';
        for (char c = 'A'; c <= 'Z'; c++) {
            if (!slots.contains(c)) {
                nextSlot = c;
                break;
            }
        }

        // If all single letters used, start with AA, BB, etc.
        if (slots.size() >= 26) {
            // For simplicity, we'll limit to 26 slots per server
            throw new IllegalStateException("Maximum slots (26) reached for server: " + baseServerId);
        }

        slots.add(nextSlot);
        String slotId = baseServerId + nextSlot;
        if (debugMode) {
            LOGGER.info("Allocated slot ID: {}", slotId);
        }
        return slotId;
    }

    /**
     * Allocate a proxy ID
     *
     * @return The allocated proxy ID
     */
    public synchronized String allocateProxyId() {
        TreeSet<Integer> available = availableNumbers.computeIfAbsent("proxy", k -> new TreeSet<>());

        int number;
        if (!available.isEmpty()) {
            // Reuse lowest available number
            number = available.pollFirst();
            if (debugMode) {
                LOGGER.debug("Reusing proxy ID number {}", number);
            }
        } else {
            // Allocate next sequential number
            AtomicInteger nextNumber = nextNumbers.computeIfAbsent("proxy", k -> new AtomicInteger(1));
            number = nextNumber.getAndIncrement();
            if (debugMode) {
                LOGGER.debug("Allocating new proxy ID number {}", number);
            }
        }

        String proxyId = "fulcrum-proxy-" + number;
        // Always log proxy ID allocation as it's essential information
        LOGGER.info("Allocated proxy ID: {}", proxyId);
        return proxyId;
    }

    /**
     * Release a server ID for reuse
     *
     * @param serverId The server ID to release
     */
    public synchronized void releaseServerId(String serverId) {
        Matcher matcher = SERVER_ID_PATTERN.matcher(serverId);
        if (matcher.matches()) {
            String type = matcher.group(1);
            int number = Integer.parseInt(matcher.group(2));
            String slot = matcher.group(3);

            if (!slot.isEmpty()) {
                // This is a slot ID, release the slot letter
                String baseId = type + number;
                TreeSet<Character> slots = allocatedSlots.get(baseId);
                if (slots != null) {
                    slots.remove(slot.charAt(0));
                    if (debugMode) {
                        LOGGER.info("Released slot ID: {}", serverId);
                    }
                }
            } else {
                // This is a base server ID, release the number
                TreeSet<Integer> available = availableNumbers.computeIfAbsent(type, k -> new TreeSet<>());
                available.add(number);

                // Also clear any allocated slots for this server
                allocatedSlots.remove(serverId);

                if (debugMode) {
                    LOGGER.info("Released server ID: {} (number {} now available for reuse)", serverId, number);
                }
            }
        }
    }

    /**
     * Release a proxy ID for reuse
     *
     * @param proxyId The proxy ID to release
     * @deprecated Use releaseProxyIdExplicit() for controlled ID release
     */
    @Deprecated
    public synchronized void releaseProxyId(String proxyId) {
        // DO NOT automatically release proxy IDs - this causes ID clashes
        // IDs should only be released through explicit action after extended timeout
        if (debugMode) {
            LOGGER.debug("Proxy ID release requested for {} but ignored (preventing ID clash)", proxyId);
        }
    }

    /**
     * Explicitly release a proxy ID for reuse after confirmation it won't cause conflicts
     *
     * @param proxyId      The proxy ID to release
     * @param forceRelease If true, forces the release even for recent disconnections
     */
    public synchronized void releaseProxyIdExplicit(String proxyId, boolean forceRelease) {
        Matcher matcher = PROXY_ID_PATTERN.matcher(proxyId);
        if (matcher.matches()) {
            int number = Integer.parseInt(matcher.group(1));
            TreeSet<Integer> available = availableNumbers.computeIfAbsent("proxy", k -> new TreeSet<>());
            available.add(number);
            LOGGER.info("Explicitly released proxy ID: {} (number {} now available for reuse, forced={})",
                    proxyId, number, forceRelease);
        }
    }

    /**
     * Check if an ID is a pool slot ID
     *
     * @param serverId The server ID to check
     * @return true if it's a slot ID (has a letter suffix)
     */
    public boolean isSlotId(String serverId) {
        Matcher matcher = SERVER_ID_PATTERN.matcher(serverId);
        return matcher.matches() && !matcher.group(3).isEmpty();
    }

    /**
     * Get the base server ID from a slot ID
     *
     * @param slotId The slot ID (e.g., mini1A)
     * @return The base server ID (e.g., mini1)
     */
    public String getBaseServerId(String slotId) {
        Matcher matcher = SERVER_ID_PATTERN.matcher(slotId);
        if (matcher.matches()) {
            return matcher.group(1) + matcher.group(2);
        }
        return slotId;
    }
}