package sh.harold.fulcrum.registry.route.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.redis.RedisScript;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis-backed persistence for player routing state. All queue entries, occupancy counters,
 * in-flight routes, party allocations, and roster state are stored here so the routing service
 * can remain stateless.
 */
public class RedisRoutingStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRoutingStore.class);

    private static final String PLAYER_QUEUE_KEY = "fulcrum:registry:routing:queue:%s";
    private static final String PARTY_QUEUE_KEY = "fulcrum:registry:routing:party:queue:%s";
    private static final String PARTY_PENDING_KEY = "fulcrum:registry:routing:party:pending:%s";
    private static final String PROVISION_LOCK_KEY = "fulcrum:registry:routing:provisioning:%s";
    private static final String OCCUPANCY_KEY = "fulcrum:registry:routing:slot-occupancy";
    private static final String IN_FLIGHT_KEY = "fulcrum:registry:routing:inflight";
    private static final String PARTY_ALLOCATIONS_KEY = "fulcrum:registry:routing:party:allocations";
    private static final String MATCH_ROSTERS_KEY = "fulcrum:registry:routing:match-rosters";
    private static final String ACTIVE_SLOTS_KEY = "fulcrum:registry:routing:active-slots";
    private static final String SLOT_PLAYERS_KEY = "fulcrum:registry:routing:slot-players:%s";
    private static final String SLOT_PLAYERS_PREFIX = "fulcrum:registry:routing:slot-players:";
    private static final String RECENT_SLOTS_KEY = "fulcrum:registry:routing:recent:%s";

    private final RedisManager redisManager;
    private final ObjectMapper objectMapper;
    private final Duration provisionLockTtl;
    private final Duration recentSlotTtl;
    private final int recentSlotHistory;
    private final RedisScript updateOccupancyScript;
    private final RedisScript setActiveSlotScript;
    private final RedisScript removeActivePlayersScript;

    public RedisRoutingStore(RedisManager redisManager) {
        this(redisManager, Duration.ofSeconds(10), Duration.ofSeconds(45), 3);
    }

    public RedisRoutingStore(RedisManager redisManager,
                             Duration provisionLockTtl,
                             Duration recentSlotTtl,
                             int recentSlotHistory) {
        this.redisManager = Objects.requireNonNull(redisManager, "redisManager");
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.provisionLockTtl = Objects.requireNonNull(provisionLockTtl, "provisionLockTtl");
        this.recentSlotTtl = Objects.requireNonNull(recentSlotTtl, "recentSlotTtl");
        this.recentSlotHistory = Math.max(1, recentSlotHistory);
        this.updateOccupancyScript = redisManager.loadScriptFromResource(
                "routing-update-occupancy",
                ScriptOutputType.INTEGER,
                "redis/scripts/routing/update_occupancy.lua"
        );
        this.setActiveSlotScript = redisManager.loadScriptFromResource(
                "routing-set-active-slot",
                ScriptOutputType.VALUE,
                "redis/scripts/routing/set_active_slot.lua"
        );
        this.removeActivePlayersScript = redisManager.loadScriptFromResource(
                "routing-remove-active-players",
                ScriptOutputType.MULTI,
                "redis/scripts/routing/remove_active_players_for_slot.lua"
        );
    }

    private static String playerQueueKey(String familyId) {
        return String.format(PLAYER_QUEUE_KEY, familyId);
    }

    private static String partyQueueKey(String familyId) {
        return String.format(PARTY_QUEUE_KEY, familyId);
    }

    private static String partyPendingKey(String reservationId) {
        return String.format(PARTY_PENDING_KEY, reservationId);
    }

    private static String provisionLockKey(String familyId) {
        return String.format(PROVISION_LOCK_KEY, familyId);
    }

    private static String slotPlayersKey(String slotId) {
        return String.format(SLOT_PLAYERS_KEY, slotId);
    }

    private static String recentSlotsKey(UUID playerId) {
        return String.format(RECENT_SLOTS_KEY, playerId);
    }

    private RedisCommands<String, String> commands() {
        return redisManager.sync();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize routing store value", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize routing store value", exception);
        }
    }

    public void enqueuePlayer(String familyId, PlayerQueueEntry entry) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(entry, "entry");
        commands().rpush(playerQueueKey(familyId), toJson(entry));
    }

    public Optional<PlayerQueueEntry> pollPlayer(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        String json = commands().lpop(playerQueueKey(familyId));
        PlayerQueueEntry entry = fromJson(json, PlayerQueueEntry.class);
        return Optional.ofNullable(entry);
    }

    public void requeuePlayer(String familyId, PlayerQueueEntry entry) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(entry, "entry");
        commands().rpush(playerQueueKey(familyId), toJson(entry));
    }

    public boolean acquireProvisionLock(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        SetArgs args = SetArgs.Builder.nx().px(provisionLockTtl.toMillis());
        String result = commands().set(provisionLockKey(familyId), Long.toString(System.currentTimeMillis()), args);
        return "OK".equalsIgnoreCase(result);
    }

    public void releaseProvisionLock(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        commands().del(provisionLockKey(familyId));
    }

    private long updateOccupancy(String slotId, long delta) {
        Objects.requireNonNull(slotId, "slotId");
        Long value = redisManager.eval(
                updateOccupancyScript,
                List.of(OCCUPANCY_KEY),
                List.of(slotId, Long.toString(delta))
        );
        return value != null ? value : 0L;
    }

    public long incrementOccupancy(String slotId) {
        return updateOccupancy(slotId, 1L);
    }

    public long decrementOccupancy(String slotId) {
        return updateOccupancy(slotId, -1L);
    }

    public long getOccupancy(String slotId) {
        Objects.requireNonNull(slotId, "slotId");
        String value = commands().hget(OCCUPANCY_KEY, slotId);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            LOGGER.warn("Encountered non-numeric occupancy for slot {}: {}", slotId, value);
            return 0L;
        }
    }

    public void storeInFlightRoute(UUID requestId, RouteEntry entry) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(entry, "entry");
        commands().hset(IN_FLIGHT_KEY, requestId.toString(), toJson(entry));
    }

    public Optional<RouteEntry> getInFlightRoute(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        String json = commands().hget(IN_FLIGHT_KEY, requestId.toString());
        return Optional.ofNullable(fromJson(json, RouteEntry.class));
    }

    public Optional<RouteEntry> removeInFlightRoute(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        String key = requestId.toString();
        String json = commands().hget(IN_FLIGHT_KEY, key);
        if (json == null) {
            return Optional.empty();
        }
        commands().hdel(IN_FLIGHT_KEY, key);
        return Optional.ofNullable(fromJson(json, RouteEntry.class));
    }

    public List<StoredRoute> getInFlightRoutes() {
        Map<String, String> payloads = commands().hgetall(IN_FLIGHT_KEY);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<StoredRoute> routes = new ArrayList<>(payloads.size());
        for (Map.Entry<String, String> entry : payloads.entrySet()) {
            RouteEntry route = fromJson(entry.getValue(), RouteEntry.class);
            if (route == null) {
                continue;
            }
            try {
                UUID requestId = UUID.fromString(entry.getKey());
                routes.add(new StoredRoute(requestId, route));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid in-flight request id {}", entry.getKey());
            }
        }
        return routes;
    }

    public void savePartyAllocation(String reservationId, PartyAllocationEntry entry) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(entry, "entry");
        commands().hset(PARTY_ALLOCATIONS_KEY, reservationId, toJson(entry));
    }

    public Optional<PartyAllocationEntry> getPartyAllocation(String reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        String json = commands().hget(PARTY_ALLOCATIONS_KEY, reservationId);
        return Optional.ofNullable(fromJson(json, PartyAllocationEntry.class));
    }

    public Optional<PartyAllocationEntry> removePartyAllocation(String reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        String json = commands().hget(PARTY_ALLOCATIONS_KEY, reservationId);
        if (json == null) {
            return Optional.empty();
        }
        commands().hdel(PARTY_ALLOCATIONS_KEY, reservationId);
        return Optional.ofNullable(fromJson(json, PartyAllocationEntry.class));
    }

    public List<PartyAllocationRecord> getPartyAllocations() {
        Map<String, String> payloads = commands().hgetall(PARTY_ALLOCATIONS_KEY);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<PartyAllocationRecord> allocations = new ArrayList<>(payloads.size());
        for (Map.Entry<String, String> entry : payloads.entrySet()) {
            PartyAllocationEntry allocation = fromJson(entry.getValue(), PartyAllocationEntry.class);
            if (allocation != null) {
                allocations.add(new PartyAllocationRecord(entry.getKey(), allocation));
            }
        }
        return allocations;
    }

    public void enqueuePartyReservation(String familyId, PartyReservationEntry entry) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(entry, "entry");
        commands().rpush(partyQueueKey(familyId), toJson(entry));
    }

    public void enqueuePartyReservationFront(String familyId, PartyReservationEntry entry) {
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(entry, "entry");
        commands().lpush(partyQueueKey(familyId), toJson(entry));
    }

    public Optional<PartyReservationEntry> pollPartyReservation(String familyId) {
        Objects.requireNonNull(familyId, "familyId");
        String json = commands().lpop(partyQueueKey(familyId));
        return Optional.ofNullable(fromJson(json, PartyReservationEntry.class));
    }

    public void enqueuePendingReservationPlayer(String reservationId, PlayerQueueEntry entry) {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(entry, "entry");
        commands().rpush(partyPendingKey(reservationId), toJson(entry));
    }

    public Optional<PlayerQueueEntry> pollPendingReservationPlayer(String reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        String json = commands().lpop(partyPendingKey(reservationId));
        return Optional.ofNullable(fromJson(json, PlayerQueueEntry.class));
    }

    public List<PlayerQueueEntry> drainPendingReservationPlayers(String reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        String key = partyPendingKey(reservationId);
        List<String> payloads = commands().lrange(key, 0, -1);
        commands().del(key);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<PlayerQueueEntry> entries = new ArrayList<>(payloads.size());
        for (String json : payloads) {
            PlayerQueueEntry entry = fromJson(json, PlayerQueueEntry.class);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public void storeMatchRoster(String slotId, MatchRosterEntry entry) {
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(entry, "entry");
        commands().hset(MATCH_ROSTERS_KEY, slotId, toJson(entry));
    }

    public Optional<MatchRosterEntry> getMatchRoster(String slotId) {
        Objects.requireNonNull(slotId, "slotId");
        String json = commands().hget(MATCH_ROSTERS_KEY, slotId);
        return Optional.ofNullable(fromJson(json, MatchRosterEntry.class));
    }

    public Optional<MatchRosterEntry> removeMatchRoster(String slotId) {
        Objects.requireNonNull(slotId, "slotId");
        String json = commands().hget(MATCH_ROSTERS_KEY, slotId);
        if (json == null) {
            return Optional.empty();
        }
        commands().hdel(MATCH_ROSTERS_KEY, slotId);
        return Optional.ofNullable(fromJson(json, MatchRosterEntry.class));
    }

    public Optional<String> setActiveSlot(UUID playerId, String slotId) {
        Objects.requireNonNull(playerId, "playerId");
        String playerKey = playerId.toString();
        String normalizedSlot = slotId != null ? slotId : "";
        String newSlotKey = normalizedSlot.isEmpty() ? "" : slotPlayersKey(normalizedSlot);
        String previous = redisManager.eval(
                setActiveSlotScript,
                List.of(ACTIVE_SLOTS_KEY, newSlotKey),
                List.of(playerKey, normalizedSlot, SLOT_PLAYERS_PREFIX)
        );
        if (previous == null || previous.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(previous);
    }

    public Optional<String> getActiveSlot(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        String value = commands().hget(ACTIVE_SLOTS_KEY, playerId.toString());
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public Optional<String> clearActiveSlot(UUID playerId) {
        return setActiveSlot(playerId, "");
    }

    @SuppressWarnings("unchecked")
    public Set<UUID> removeActivePlayersForSlot(String slotId) {
        Objects.requireNonNull(slotId, "slotId");
        Object result = redisManager.eval(
                removeActivePlayersScript,
                List.of(slotPlayersKey(slotId), ACTIVE_SLOTS_KEY),
                Collections.emptyList()
        );
        if (!(result instanceof List<?> rawList) || rawList.isEmpty()) {
            return Set.of();
        }
        Set<UUID> players = new LinkedHashSet<>();
        for (Object item : rawList) {
            if (item instanceof String value && !value.isBlank()) {
                try {
                    players.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Ignoring invalid UUID while clearing slot {}: {}", slotId, value);
                }
            }
        }
        return players;
    }

    public void pushRecentSlot(UUID playerId, String slotId, long recordedAt) {
        Objects.requireNonNull(playerId, "playerId");
        if (slotId == null || slotId.isBlank()) {
            return;
        }
        String key = recentSlotsKey(playerId);
        RecentSlotEntry entry = new RecentSlotEntry(slotId, recordedAt);
        commands().lpush(key, toJson(entry));
        commands().ltrim(key, 0, recentSlotHistory - 1);
        if (!recentSlotTtl.isZero() && !recentSlotTtl.isNegative()) {
            commands().pexpire(key, recentSlotTtl.toMillis());
        }
    }

    public List<String> getRecentSlots(UUID playerId, long now) {
        Objects.requireNonNull(playerId, "playerId");
        String key = recentSlotsKey(playerId);
        List<String> payloads = commands().lrange(key, 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }

        List<RecentSlotEntry> entries = new ArrayList<>(payloads.size());
        for (String json : payloads) {
            RecentSlotEntry entry = fromJson(json, RecentSlotEntry.class);
            if (entry != null) {
                entries.add(entry);
            }
        }

        List<RecentSlotEntry> filtered = entries.stream()
                .filter(entry -> now - entry.recordedAt <= recentSlotTtl.toMillis())
                .collect(Collectors.toList());

        if (filtered.size() != entries.size()) {
            commands().del(key);
            if (!filtered.isEmpty()) {
                List<String> serialized = filtered.stream()
                        .map(this::toJson)
                        .collect(Collectors.toList());
                commands().rpush(key, serialized.toArray(String[]::new));
                commands().ltrim(key, 0, recentSlotHistory - 1);
                if (!recentSlotTtl.isZero() && !recentSlotTtl.isNegative()) {
                    commands().pexpire(key, recentSlotTtl.toMillis());
                }
            }
        }

        return filtered.stream()
                .map(RecentSlotEntry::slotId)
                .collect(Collectors.toList());
    }

    public void trimRecentSlots(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        String key = recentSlotsKey(playerId);
        commands().ltrim(key, 0, recentSlotHistory - 1);
        if (!recentSlotTtl.isZero() && !recentSlotTtl.isNegative()) {
            commands().pexpire(key, recentSlotTtl.toMillis());
        }
    }

    public record StoredRoute(UUID requestId, RouteEntry entry) {
    }

    public record PartyAllocationRecord(String reservationId, PartyAllocationEntry entry) {
    }

    public static final class PlayerQueueEntry {
        private PlayerSlotRequest request;
        private long createdAt;
        private long lastEnqueuedAt;
        private String currentSlotId;
        private List<String> blockedSlotIds = List.of();
        private String variantId;
        private String preferredSlotId;
        private boolean rejoin;
        private int retries;

        public PlayerQueueEntry() {
        }

        public PlayerQueueEntry(PlayerSlotRequest request,
                                long createdAt,
                                long lastEnqueuedAt,
                                String currentSlotId,
                                List<String> blockedSlotIds,
                                String variantId,
                                String preferredSlotId,
                                boolean rejoin,
                                int retries) {
            this.request = request;
            this.createdAt = createdAt;
            this.lastEnqueuedAt = lastEnqueuedAt;
            this.currentSlotId = currentSlotId;
            this.blockedSlotIds = blockedSlotIds != null
                    ? List.copyOf(blockedSlotIds)
                    : List.of();
            this.variantId = variantId;
            this.preferredSlotId = preferredSlotId;
            this.rejoin = rejoin;
            this.retries = retries;
        }

        public PlayerSlotRequest getRequest() {
            return request;
        }

        public void setRequest(PlayerSlotRequest request) {
            this.request = request;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public long getLastEnqueuedAt() {
            return lastEnqueuedAt;
        }

        public void setLastEnqueuedAt(long lastEnqueuedAt) {
            this.lastEnqueuedAt = lastEnqueuedAt;
        }

        public String getCurrentSlotId() {
            return currentSlotId;
        }

        public void setCurrentSlotId(String currentSlotId) {
            this.currentSlotId = currentSlotId;
        }

        public List<String> getBlockedSlotIds() {
            return blockedSlotIds;
        }

        public void setBlockedSlotIds(List<String> blockedSlotIds) {
            this.blockedSlotIds = blockedSlotIds != null
                    ? List.copyOf(blockedSlotIds)
                    : List.of();
        }

        public String getVariantId() {
            return variantId;
        }

        public void setVariantId(String variantId) {
            this.variantId = variantId;
        }

        public String getPreferredSlotId() {
            return preferredSlotId;
        }

        public void setPreferredSlotId(String preferredSlotId) {
            this.preferredSlotId = preferredSlotId;
        }

        public boolean isRejoin() {
            return rejoin;
        }

        public void setRejoin(boolean rejoin) {
            this.rejoin = rejoin;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }
    }

    public static final class RouteEntry {
        private PlayerQueueEntry context;
        private String slotId;
        private long createdAt;

        public RouteEntry() {
        }

        public RouteEntry(PlayerQueueEntry context, String slotId, long createdAt) {
            this.context = context;
            this.slotId = slotId;
            this.createdAt = createdAt;
        }

        public PlayerQueueEntry getContext() {
            return context;
        }

        public void setContext(PlayerQueueEntry context) {
            this.context = context;
        }

        public String getSlotId() {
            return slotId;
        }

        public void setSlotId(String slotId) {
            this.slotId = slotId;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static final class PartyReservationEntry {
        private PartyReservationSnapshot reservation;
        private String familyId;
        private String variantId;
        private int partySize;
        private long enqueuedAt;

        public PartyReservationEntry() {
        }

        public PartyReservationEntry(PartyReservationSnapshot reservation,
                                     String familyId,
                                     String variantId,
                                     int partySize,
                                     long enqueuedAt) {
            this.reservation = reservation;
            this.familyId = familyId;
            this.variantId = variantId;
            this.partySize = partySize;
            this.enqueuedAt = enqueuedAt;
        }

        public PartyReservationSnapshot getReservation() {
            return reservation;
        }

        public void setReservation(PartyReservationSnapshot reservation) {
            this.reservation = reservation;
        }

        public String getFamilyId() {
            return familyId;
        }

        public void setFamilyId(String familyId) {
            this.familyId = familyId;
        }

        public String getVariantId() {
            return variantId;
        }

        public void setVariantId(String variantId) {
            this.variantId = variantId;
        }

        public int getPartySize() {
            return partySize;
        }

        public void setPartySize(int partySize) {
            this.partySize = partySize;
        }

        public long getEnqueuedAt() {
            return enqueuedAt;
        }

        public void setEnqueuedAt(long enqueuedAt) {
            this.enqueuedAt = enqueuedAt;
        }
    }

    public static final class PartyAllocationEntry {
        private PartyReservationSnapshot reservation;
        private String reservationId;
        private String familyId;
        private String variantId;
        private String slotId;
        private String slotSuffix;
        private String serverId;
        private int partySize;
        private int teamIndex;
        private boolean released;
        private long allocatedAt;
        private Set<UUID> dispatchedPlayers = Set.of();
        private Set<UUID> claimedPlayers = Set.of();
        private Map<UUID, String> claimFailures = Map.of();

        public PartyAllocationEntry() {
        }

        public PartyAllocationEntry(PartyReservationSnapshot reservation,
                                    String reservationId,
                                    String familyId,
                                    String variantId,
                                    String slotId,
                                    String slotSuffix,
                                    String serverId,
                                    int partySize,
                                    int teamIndex,
                                    boolean released,
                                    long allocatedAt,
                                    Set<UUID> dispatchedPlayers,
                                    Set<UUID> claimedPlayers,
                                    Map<UUID, String> claimFailures) {
            this.reservation = reservation;
            this.reservationId = reservationId;
            this.familyId = familyId;
            this.variantId = variantId;
            this.slotId = slotId;
            this.slotSuffix = slotSuffix;
            this.serverId = serverId;
            this.partySize = partySize;
            this.teamIndex = teamIndex;
            this.released = released;
            this.allocatedAt = allocatedAt;
            this.dispatchedPlayers = dispatchedPlayers != null
                    ? Set.copyOf(dispatchedPlayers)
                    : Set.of();
            this.claimedPlayers = claimedPlayers != null
                    ? Set.copyOf(claimedPlayers)
                    : Set.of();
            this.claimFailures = claimFailures != null
                    ? Map.copyOf(claimFailures)
                    : Map.of();
        }

        public PartyReservationSnapshot getReservation() {
            return reservation;
        }

        public void setReservation(PartyReservationSnapshot reservation) {
            this.reservation = reservation;
        }

        public String getReservationId() {
            return reservationId;
        }

        public void setReservationId(String reservationId) {
            this.reservationId = reservationId;
        }

        public String getFamilyId() {
            return familyId;
        }

        public void setFamilyId(String familyId) {
            this.familyId = familyId;
        }

        public String getVariantId() {
            return variantId;
        }

        public void setVariantId(String variantId) {
            this.variantId = variantId;
        }

        public String getSlotId() {
            return slotId;
        }

        public void setSlotId(String slotId) {
            this.slotId = slotId;
        }

        public String getSlotSuffix() {
            return slotSuffix;
        }

        public void setSlotSuffix(String slotSuffix) {
            this.slotSuffix = slotSuffix;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public int getPartySize() {
            return partySize;
        }

        public void setPartySize(int partySize) {
            this.partySize = partySize;
        }

        public int getTeamIndex() {
            return teamIndex;
        }

        public void setTeamIndex(int teamIndex) {
            this.teamIndex = teamIndex;
        }

        public boolean isReleased() {
            return released;
        }

        public void setReleased(boolean released) {
            this.released = released;
        }

        public long getAllocatedAt() {
            return allocatedAt;
        }

        public void setAllocatedAt(long allocatedAt) {
            this.allocatedAt = allocatedAt;
        }

        public Set<UUID> getDispatchedPlayers() {
            return dispatchedPlayers;
        }

        public void setDispatchedPlayers(Set<UUID> dispatchedPlayers) {
            this.dispatchedPlayers = dispatchedPlayers != null
                    ? Collections.unmodifiableSet(new HashSet<>(dispatchedPlayers))
                    : Set.of();
        }

        public Set<UUID> getClaimedPlayers() {
            return claimedPlayers;
        }

        public void setClaimedPlayers(Set<UUID> claimedPlayers) {
            this.claimedPlayers = claimedPlayers != null
                    ? Collections.unmodifiableSet(new HashSet<>(claimedPlayers))
                    : Set.of();
        }

        public Map<UUID, String> getClaimFailures() {
            return claimFailures;
        }

        public void setClaimFailures(Map<UUID, String> claimFailures) {
            this.claimFailures = claimFailures != null
                    ? Map.copyOf(claimFailures)
                    : Map.of();
        }
    }

    public static final class MatchRosterEntry {
        private UUID matchId;
        private Set<UUID> players = Set.of();
        private long updatedAt;

        public MatchRosterEntry() {
        }

        public MatchRosterEntry(UUID matchId, Set<UUID> players, long updatedAt) {
            this.matchId = matchId;
            this.players = players != null ? Set.copyOf(players) : Set.of();
            this.updatedAt = updatedAt;
        }

        public UUID getMatchId() {
            return matchId;
        }

        public void setMatchId(UUID matchId) {
            this.matchId = matchId;
        }

        public Set<UUID> getPlayers() {
            return players;
        }

        public void setPlayers(Set<UUID> players) {
            this.players = players != null ? Set.copyOf(players) : Set.of();
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static final class RecentSlotEntry {
        private String slotId;
        private long recordedAt;

        public RecentSlotEntry() {
        }

        public RecentSlotEntry(String slotId, long recordedAt) {
            this.slotId = slotId;
            this.recordedAt = recordedAt;
        }

        public String slotId() {
            return slotId;
        }

        public void setSlotId(String slotId) {
            this.slotId = slotId;
        }

        public long recordedAt() {
            return recordedAt;
        }

        public void setRecordedAt(long recordedAt) {
            this.recordedAt = recordedAt;
        }
    }
}
