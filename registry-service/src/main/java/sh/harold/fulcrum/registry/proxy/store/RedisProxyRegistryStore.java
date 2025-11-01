package sh.harold.fulcrum.registry.proxy.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.state.RegistrationState;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Redis persistence layer for proxy registry state.
 */
public class RedisProxyRegistryStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisProxyRegistryStore.class);

    private static final String ACTIVE_KEY_PREFIX = "fulcrum:registry:proxies:active:";
    private static final String UNAVAILABLE_KEY_PREFIX = "fulcrum:registry:proxies:unavailable:";
    private static final String TEMP_INDEX_KEY = "fulcrum:registry:proxies:index:temp";
    private static final String ADDRESS_INDEX_KEY = "fulcrum:registry:proxies:index:address";

    private final RedisManager redisManager;
    private final ObjectMapper objectMapper;

    public RedisProxyRegistryStore(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static String activeKey(String proxyId) {
        return ACTIVE_KEY_PREFIX + proxyId;
    }

    private static String unavailableKey(String proxyId) {
        return UNAVAILABLE_KEY_PREFIX + proxyId;
    }

    private static String addressKey(String address, int port) {
        return address + ":" + port;
    }

    public void saveActive(RegisteredProxyData data) {
        ProxyDocument document = ProxyDocument.from(data);
        writeDocument(activeKey(data.getProxyIdString()), document);
        RedisCommands<String, String> commands = redisManager.sync();
        commands.hset(ADDRESS_INDEX_KEY, addressKey(data.getAddress(), data.getPort()), data.getProxyIdString());
    }

    public void saveUnavailable(RegisteredProxyData data, long unavailableSince) {
        UnavailableProxyDocument document = UnavailableProxyDocument.from(data, unavailableSince);
        writeDocument(unavailableKey(data.getProxyIdString()), document);
        redisManager.sync().hdel(ADDRESS_INDEX_KEY, addressKey(data.getAddress(), data.getPort()));
    }

    public void deleteActive(RegisteredProxyData data) {
        RedisCommands<String, String> commands = redisManager.sync();
        commands.del(activeKey(data.getProxyIdString()));
        commands.hdel(ADDRESS_INDEX_KEY, addressKey(data.getAddress(), data.getPort()));
    }

    public void deleteUnavailable(String proxyId) {
        redisManager.sync().del(unavailableKey(proxyId));
    }

    public List<RegisteredProxyData> loadActive(ScheduledExecutorService executor) {
        RedisCommands<String, String> commands = redisManager.sync();
        List<RegisteredProxyData> result = new ArrayList<>();
        for (String key : commands.keys(ACTIVE_KEY_PREFIX + "*")) {
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                ProxyDocument document = objectMapper.readValue(payload, ProxyDocument.class);
                result.add(document.toDomain(executor));
            } catch (IOException ex) {
                LOGGER.error("Failed to deserialise proxy document from {}", key, ex);
            }
        }
        return result;
    }

    public Map<RegisteredProxyData, Long> loadUnavailable(ScheduledExecutorService executor) {
        RedisCommands<String, String> commands = redisManager.sync();
        Map<RegisteredProxyData, Long> result = new LinkedHashMap<>();
        for (String key : commands.keys(UNAVAILABLE_KEY_PREFIX + "*")) {
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                UnavailableProxyDocument document = objectMapper.readValue(payload, UnavailableProxyDocument.class);
                RegisteredProxyData proxy = document.toDomain(executor);
                result.put(proxy, document.unavailableSince());
            } catch (IOException ex) {
                LOGGER.error("Failed to deserialise unavailable proxy document from {}", key, ex);
            }
        }
        return result;
    }

    public Map<String, String> loadTempMappings() {
        return redisManager.sync().hgetall(TEMP_INDEX_KEY);
    }

    public void upsertTempMapping(String tempId, String proxyId) {
        if (tempId != null && !tempId.isBlank()) {
            redisManager.sync().hset(TEMP_INDEX_KEY, tempId, proxyId);
        }
    }

    public void removeTempMapping(String tempId) {
        if (tempId != null && !tempId.isBlank()) {
            redisManager.sync().hdel(TEMP_INDEX_KEY, tempId);
        }
    }

    public Optional<String> findProxyIdByTemp(String tempId) {
        if (tempId == null || tempId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redisManager.sync().hget(TEMP_INDEX_KEY, tempId));
    }

    private void writeDocument(String key, Object document) {
        try {
            String payload = objectMapper.writeValueAsString(document);
            redisManager.sync().set(key, payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise proxy document for key " + key, ex);
        }
    }

    private record ProxyDocument(
            String proxyId,
            String address,
            int port,
            String status,
            long lastHeartbeat,
            String registrationState
    ) {
        static ProxyDocument from(RegisteredProxyData data) {
            return new ProxyDocument(
                    data.getProxyIdString(),
                    data.getAddress(),
                    data.getPort(),
                    data.getStatus().name(),
                    data.getLastHeartbeat(),
                    data.getRegistrationState().name()
            );
        }

        RegisteredProxyData toDomain(ScheduledExecutorService executor) {
            ProxyIdentifier identifier = ProxyIdentifier.parse(proxyId);
            RegisteredProxyData data = new RegisteredProxyData(identifier, address, port, executor);
            data.setStatus(RegisteredProxyData.Status.valueOf(status));
            data.setLastHeartbeat(lastHeartbeat);
            data.getStateMachine().forceSetState(RegistrationState.valueOf(registrationState), "Restored from Redis");
            return data;
        }
    }

    private record UnavailableProxyDocument(
            ProxyDocument proxy,
            long unavailableSince
    ) {
        static UnavailableProxyDocument from(RegisteredProxyData data, long unavailableSince) {
            return new UnavailableProxyDocument(ProxyDocument.from(data), unavailableSince);
        }

        RegisteredProxyData toDomain(ScheduledExecutorService executor) {
            RegisteredProxyData data = proxy.toDomain(executor);
            data.setStatus(RegisteredProxyData.Status.UNAVAILABLE);
            return data;
        }
    }
}
