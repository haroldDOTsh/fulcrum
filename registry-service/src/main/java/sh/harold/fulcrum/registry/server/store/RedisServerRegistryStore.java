package sh.harold.fulcrum.registry.server.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.io.IOException;
import java.util.*;

/**
 * Redis persistence layer for server registry records.
 */
public class RedisServerRegistryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisServerRegistryStore.class);

    private static final String SERVER_KEY_PREFIX = "fulcrum:registry:servers:";
    private static final String TEMP_INDEX_KEY = "fulcrum:registry:servers:index:temp";

    private final RedisManager redisManager;
    private final ObjectMapper objectMapper;

    public RedisServerRegistryStore(RedisManager redisManager) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static String serverKey(String serverId) {
        return SERVER_KEY_PREFIX + serverId;
    }

    public void save(RegisteredServerData server) {
        Objects.requireNonNull(server, "server");
        ServerDocument document = ServerDocument.from(server);
        try {
            String payload = objectMapper.writeValueAsString(document);
            RedisCommands<String, String> commands = redisManager.sync();
            commands.set(serverKey(server.getServerId()), payload);
            if (server.getTempId() != null && !server.getTempId().isBlank()) {
                commands.hset(TEMP_INDEX_KEY, server.getTempId(), server.getServerId());
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise server document for " + server.getServerId(), ex);
        }
    }

    public void delete(String serverId, String tempId) {
        RedisCommands<String, String> commands = redisManager.sync();
        commands.del(serverKey(serverId));
        if (tempId != null && !tempId.isBlank()) {
            commands.hdel(TEMP_INDEX_KEY, tempId);
        }
    }

    public List<RegisteredServerData> loadAll() {
        RedisCommands<String, String> commands = redisManager.sync();
        List<RegisteredServerData> results = new ArrayList<>();
        for (String key : commands.keys(SERVER_KEY_PREFIX + "*")) {
            if (TEMP_INDEX_KEY.equals(key)) {
                continue;
            }
            String type = commands.type(key);
            if (!"string".equalsIgnoreCase(type)) {
                continue;
            }
            String payload = commands.get(key);
            if (payload == null || payload.isBlank()) {
                continue;
            }
            try {
                ServerDocument document = objectMapper.readValue(payload, ServerDocument.class);
                results.add(document.toDomain());
            } catch (IOException ex) {
                LOGGER.error("Failed to deserialise server document at key {}", key, ex);
            }
        }
        return results;
    }

    public Optional<String> findPermanentIdByTemp(String tempId) {
        if (tempId == null || tempId.isBlank()) {
            return Optional.empty();
        }
        String value = redisManager.sync().hget(TEMP_INDEX_KEY, tempId);
        return Optional.ofNullable(value);
    }

    public record ServerDocument(
            String serverId,
            String tempId,
            String serverType,
            String address,
            int port,
            int maxCapacity,
            String role,
            String status,
            long lastHeartbeat,
            int playerCount,
            double tps,
            double memoryUsage,
            double cpuUsage,
            Map<String, SlotDocument> slots,
            Map<String, Integer> slotFamilyCapacities,
            Map<String, List<String>> slotFamilyVariants,
            String fulcrumVersion
    ) {
        public static ServerDocument from(RegisteredServerData data) {
            Map<String, SlotDocument> slotDocs = new LinkedHashMap<>();
            for (LogicalSlotRecord record : data.getSlots()) {
                slotDocs.put(record.getSlotSuffix(), SlotDocument.from(record));
            }

            Map<String, Integer> capacities = new LinkedHashMap<>(data.getSlotFamilyCapacities());

            Map<String, List<String>> variants = new LinkedHashMap<>();
            data.getSlotFamilyVariants().forEach((family, values) -> variants.put(family, new ArrayList<>(values)));

            return new ServerDocument(
                    data.getServerId(),
                    data.getTempId(),
                    data.getServerType(),
                    data.getAddress(),
                    data.getPort(),
                    data.getMaxCapacity(),
                    data.getRole(),
                    data.getStatus().name(),
                    data.getLastHeartbeat(),
                    data.getPlayerCount(),
                    data.getTps(),
                    data.getMemoryUsage(),
                    data.getCpuUsage(),
                    slotDocs,
                    capacities,
                    variants,
                    data.getFulcrumVersion()
            );
        }

        public RegisteredServerData toDomain() {
            RegisteredServerData server = new RegisteredServerData(
                    serverId,
                    tempId,
                    serverType,
                    address,
                    port,
                    maxCapacity
            );
            server.setRole(role != null ? role : "default");
            if (status != null) {
                try {
                    server.setStatus(RegisteredServerData.Status.valueOf(status));
                } catch (IllegalArgumentException ignored) {
                    // fall back to STARTING
                }
            }
            server.setLastHeartbeat(lastHeartbeat);
            server.setPlayerCount(playerCount);
            server.setTps(tps);
            server.setMemoryUsage(memoryUsage);
            server.setCpuUsage(cpuUsage);
            server.setFulcrumVersion(fulcrumVersion);

            if (slots != null) {
                slots.values().forEach(slotDoc -> slotDoc.applyTo(server));
            }

            if (slotFamilyCapacities != null) {
                server.updateSlotFamilyCapacities(slotFamilyCapacities);
            }
            if (slotFamilyVariants != null) {
                Map<String, Set<String>> converted = new LinkedHashMap<>();
                slotFamilyVariants.forEach((family, values) -> {
                    if (values != null) {
                        converted.put(family, new LinkedHashSet<>(values));
                    }
                });
                server.updateSlotFamilyVariants(converted);
            }

            return server;
        }
    }

    public record SlotDocument(
            String slotId,
            String slotSuffix,
            String gameType,
            String status,
            int maxPlayers,
            int onlinePlayers,
            long lastUpdated,
            Map<String, String> metadata
    ) {
        public static SlotDocument from(LogicalSlotRecord record) {
            return new SlotDocument(
                    record.getSlotId(),
                    record.getSlotSuffix(),
                    record.getGameType(),
                    record.getStatus() != null ? record.getStatus().name() : SlotLifecycleStatus.PROVISIONING.name(),
                    record.getMaxPlayers(),
                    record.getOnlinePlayers(),
                    record.getLastUpdated(),
                    new LinkedHashMap<>(record.getMetadata())
            );
        }

        public void applyTo(RegisteredServerData server) {
            LogicalSlotRecord record = new LogicalSlotRecord(slotId, slotSuffix, server.getServerId());
            record.setGameType(gameType);
            if (status != null) {
                try {
                    record.setStatus(SlotLifecycleStatus.valueOf(status));
                } catch (IllegalArgumentException ignored) {
                    record.setStatus(SlotLifecycleStatus.PROVISIONING);
                }
            }
            record.setMaxPlayers(maxPlayers);
            record.setOnlinePlayers(onlinePlayers);
            record.setLastUpdated(lastUpdated);
            record.replaceMetadata(metadata != null ? metadata : Map.of());
            server.putSlot(record);
        }
    }
}
