package sh.harold.fulcrum.fundamentals.lifecycle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates server IDs in the format: <servertype><number>[<poolslot>]
 * Examples: mini1, mini2, mega3, lobby1a, lobby1b
 */
public class ServerIdGenerator {

    private static final Map<String, AtomicInteger> typeCounters = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> poolSlotCounters = new ConcurrentHashMap<>();

    /**
     * Generate a server ID for the given type
     *
     * @param serverType The server type (e.g., "mini", "mega", "lobby")
     * @return Generated server ID (e.g., "mini1", "mega2")
     */
    public static String generateId(String serverType) {
        String type = serverType.toLowerCase();
        AtomicInteger counter = typeCounters.computeIfAbsent(type, k -> new AtomicInteger(0));
        int number = counter.incrementAndGet();
        return type + number;
    }

    /**
     * Generate a server ID with a pool slot
     *
     * @param serverType The server type
     * @param poolId     The pool identifier
     * @return Generated server ID with pool slot (e.g., "lobby1a", "lobby1b")
     */
    public static String generateIdWithPool(String serverType, String poolId) {
        String type = serverType.toLowerCase();
        String baseId = type + poolId;
        AtomicInteger slotCounter = poolSlotCounters.computeIfAbsent(baseId, k -> new AtomicInteger(0));
        int slot = slotCounter.incrementAndGet();

        // Convert slot number to letter (1=a, 2=b, etc.)
        char slotLetter = (char) ('a' + slot - 1);
        return baseId + slotLetter;
    }

    /**
     * Reset all counters (useful for testing)
     */
    public static void resetCounters() {
        typeCounters.clear();
        poolSlotCounters.clear();
    }
}