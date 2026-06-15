package sh.harold.fulcrum.registry.authority;

import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.time.Duration;

/**
 * Compatibility alias for the Redis wire-protocol cache adapter.
 */
@Deprecated(forRemoval = false)
public final class RedisAuthorityCommandResultCache extends ValkeyAuthorityCommandResultCache {
    public RedisAuthorityCommandResultCache(MessageBusConnectionConfig config, Duration ttl) {
        super(config, ttl);
    }
}
