package sh.harold.fulcrum.registry.allocation;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.redis.RedisScript;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages contiguous ID allocation for servers, slots, and proxies backed by Redis.
 */
public class IdAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdAllocator.class);

    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("^(mini|mega|pool)(\\d+)([A-Z]?)$");
    private static final Pattern PROXY_ID_PATTERN = Pattern.compile("^fulcrum-proxy-(\\d+)$");
    private static final int MAX_SLOT_SUFFIX = 26;

    private static final String SERVER_COUNTER_KEY = "fulcrum:registry:id:server:%s:counter";
    private static final String SERVER_RECYCLE_KEY = "fulcrum:registry:id:server:%s:recycle";
    private static final String PROXY_COUNTER_KEY = "fulcrum:registry:id:proxy:counter";
    private static final String PROXY_RECYCLE_KEY = "fulcrum:registry:id:proxy:recycle";
    private static final String SLOT_COUNTER_KEY = "fulcrum:registry:id:slot:%s:counter";
    private static final String SLOT_RECYCLE_KEY = "fulcrum:registry:id:slot:%s:recycle";

    private volatile RedisManager redisManager;
    private RedisScript allocateNumericIdScript;
    private RedisScript allocateSlotSuffixScript;
    private RedisScript claimNumericIdScript;

    private boolean debugMode;

    public IdAllocator() {
        this(false);
    }

    public IdAllocator(boolean debugMode) {
        this.debugMode = debugMode;
    }

    private static String serverCounterKey(String type) {
        return String.format(SERVER_COUNTER_KEY, type);
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    private static String serverRecycleKey(String type) {
        return String.format(SERVER_RECYCLE_KEY, type);
    }

    private static String slotCounterKey(String baseServerId) {
        return String.format(SLOT_COUNTER_KEY, baseServerId);
    }

    private static String slotRecycleKey(String baseServerId) {
        return String.format(SLOT_RECYCLE_KEY, baseServerId);
    }

    public void initialize(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.allocateNumericIdScript = redisManager.loadScriptFromResource(
                "id-allocate-numeric",
                ScriptOutputType.VALUE,
                "redis/scripts/id/allocate_numeric.lua"
        );
        this.allocateSlotSuffixScript = redisManager.loadScriptFromResource(
                "id-allocate-slot-suffix",
                ScriptOutputType.VALUE,
                "redis/scripts/id/allocate_slot.lua"
        );
        this.claimNumericIdScript = redisManager.loadScriptFromResource(
                "id-claim-numeric",
                ScriptOutputType.STATUS,
                "redis/scripts/id/claim_numeric.lua"
        );
    }

    public String allocateServerId(String serverType) {
        String normalizedType = Objects.requireNonNull(serverType, "serverType").toLowerCase();
        RedisManager manager = requireRedisManager();

        String recycleKey = serverRecycleKey(normalizedType);
        String counterKey = serverCounterKey(normalizedType);

        String serverId = manager.eval(
                allocateNumericIdScript,
                List.of(recycleKey, counterKey),
                List.of(normalizedType)
        );

        if (debugMode) {
            LOGGER.info("Allocated server ID: {}", serverId);
        }
        return serverId;
    }

    public String allocateSlotId(String baseServerId) {
        Objects.requireNonNull(baseServerId, "baseServerId");
        RedisManager manager = requireRedisManager();

        String recycleKey = slotRecycleKey(baseServerId);
        String counterKey = slotCounterKey(baseServerId);

        try {
            String suffix = manager.eval(
                    allocateSlotSuffixScript,
                    List.of(recycleKey, counterKey),
                    List.of(String.valueOf(MAX_SLOT_SUFFIX))
            );

            if (suffix == null) {
                throw new IllegalStateException("Failed to allocate slot suffix for " + baseServerId);
            }

            if (debugMode) {
                LOGGER.info("Allocated slot ID: {}{}", baseServerId, suffix);
            }

            return baseServerId + suffix;
        } catch (RedisCommandExecutionException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("SLOT_LIMIT_REACHED")) {
                throw new IllegalStateException("Maximum slots (" + MAX_SLOT_SUFFIX + ") reached for server: " + baseServerId, ex);
            }
            throw ex;
        }
    }

    public String allocateProxyId() {
        RedisManager manager = requireRedisManager();

        String proxyId = manager.eval(
                allocateNumericIdScript,
                List.of(PROXY_RECYCLE_KEY, PROXY_COUNTER_KEY),
                List.of("fulcrum-proxy-")
        );

        LOGGER.info("Allocated proxy ID: {}", proxyId);
        return proxyId;
    }

    public boolean isSlotId(String serverId) {
        Matcher matcher = SERVER_ID_PATTERN.matcher(serverId);
        return matcher.matches() && !matcher.group(3).isEmpty();
    }

    public String getBaseServerId(String slotId) {
        Matcher matcher = SERVER_ID_PATTERN.matcher(slotId);
        if (matcher.matches()) {
            return matcher.group(1) + matcher.group(2);
        }
        return slotId;
    }

    public void releaseServerId(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        RedisManager manager = requireRedisManager();
        Matcher matcher = SERVER_ID_PATTERN.matcher(serverId);
        if (!matcher.matches()) {
            LOGGER.warn("Attempted to release invalid server ID: {}", serverId);
            return;
        }

        String type = matcher.group(1);
        int number = Integer.parseInt(matcher.group(2));
        String slotSuffix = matcher.group(3);

        if (!slotSuffix.isEmpty()) {
            String baseId = type + number;
            releaseSlotId(baseId, slotSuffix.charAt(0));
            return;
        }

        String recycleKey = serverRecycleKey(type);
        RedisCommands<String, String> commands = manager.sync();
        commands.zadd(recycleKey, number, serverId);

        // Reset slot tracking for this server
        commands.del(slotRecycleKey(serverId), slotCounterKey(serverId));

        if (debugMode) {
            LOGGER.info("Released server ID: {}", serverId);
        }
    }

    public void claimServerId(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        RedisManager manager = requireRedisManager();
        Matcher matcher = SERVER_ID_PATTERN.matcher(serverId);
        if (!matcher.matches()) {
            return;
        }

        String type = matcher.group(1);
        int number = Integer.parseInt(matcher.group(2));
        String recycleKey = serverRecycleKey(type);
        String counterKey = serverCounterKey(type);

        manager.eval(
                claimNumericIdScript,
                List.of(recycleKey, counterKey),
                List.of(serverId, Integer.toString(number))
        );
    }

    @Deprecated
    public void releaseProxyId(String proxyId) {
        if (debugMode) {
            LOGGER.debug("Proxy ID release requested for {} but ignored (preventing ID clash)", proxyId);
        }
    }

    public void releaseProxyIdExplicit(String proxyId, boolean forceRelease) {
        Objects.requireNonNull(proxyId, "proxyId");
        RedisManager manager = requireRedisManager();
        Matcher matcher = PROXY_ID_PATTERN.matcher(proxyId);
        if (!matcher.matches()) {
            LOGGER.warn("Attempted to release invalid proxy ID: {}", proxyId);
            return;
        }

        int number = Integer.parseInt(matcher.group(1));
        manager.sync().zadd(PROXY_RECYCLE_KEY, number, proxyId);

        LOGGER.info(
                "Explicitly released proxy ID: {} (number {} now available for reuse, forced={})",
                proxyId,
                number,
                forceRelease
        );
    }

    private RedisManager requireRedisManager() {
        RedisManager manager = this.redisManager;
        if (manager == null) {
            throw new IllegalStateException("IdAllocator has not been initialised with a RedisManager");
        }
        return manager;
    }

    private void releaseSlotId(String baseServerId, char suffix) {
        RedisManager manager = requireRedisManager();
        int score = suffix - 'A' + 1;
        manager.sync().zadd(slotRecycleKey(baseServerId), score, Character.toString(suffix));

        if (debugMode) {
            LOGGER.info("Released slot ID: {}{}", baseServerId, suffix);
        }
    }
}
