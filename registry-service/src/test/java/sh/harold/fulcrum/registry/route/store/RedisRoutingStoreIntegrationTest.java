package sh.harold.fulcrum.registry.route.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.messagebus.messages.PlayerSlotRequest;
import sh.harold.fulcrum.api.party.PartyReservationSnapshot;
import sh.harold.fulcrum.api.party.PartyReservationToken;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRoutingStoreIntegrationTest extends RedisIntegrationTestSupport {

    private RedisRoutingStore newStore(RedisManager manager) {
        return new RedisRoutingStore(manager);
    }

    private PlayerSlotRequest newRequest(UUID playerId, UUID requestId, String familyId) {
        PlayerSlotRequest request = new PlayerSlotRequest();
        request.setRequestId(requestId);
        request.setPlayerId(playerId);
        request.setPlayerName("Player-" + playerId.toString().substring(0, 4));
        request.setProxyId("fulcrum-proxy-1");
        request.setFamilyId(familyId);
        return request;
    }

    @Test
    @DisplayName("Player queue preserves FIFO order")
    void playerQueueRoundTrip() {
        try (RedisManager manager = newRedisManager()) {
            RedisRoutingStore store = newStore(manager);
            UUID playerOne = UUID.randomUUID();
            UUID playerTwo = UUID.randomUUID();

            RedisRoutingStore.PlayerQueueEntry entryOne = new RedisRoutingStore.PlayerQueueEntry(
                    newRequest(playerOne, UUID.randomUUID(), "mini"),
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    null,
                    List.of(),
                    null,
                    null,
                    false,
                    0
            );
            RedisRoutingStore.PlayerQueueEntry entryTwo = new RedisRoutingStore.PlayerQueueEntry(
                    newRequest(playerTwo, UUID.randomUUID(), "mini"),
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    null,
                    List.of(),
                    null,
                    null,
                    false,
                    0
            );

            store.enqueuePlayer("mini", entryOne);
            store.enqueuePlayer("mini", entryTwo);

            assertThat(store.pollPlayer("mini").orElseThrow().getRequest().getPlayerId())
                    .isEqualTo(playerOne);
            assertThat(store.pollPlayer("mini").orElseThrow().getRequest().getPlayerId())
                    .isEqualTo(playerTwo);
            assertThat(store.pollPlayer("mini")).isEmpty();
        }
    }

    @Test
    @DisplayName("In-flight routes survive store round-trip")
    void inFlightRoutesPersist() {
        try (RedisManager manager = newRedisManager()) {
            RedisRoutingStore store = newStore(manager);
            UUID requestId = UUID.randomUUID();
            RedisRoutingStore.PlayerQueueEntry context = new RedisRoutingStore.PlayerQueueEntry(
                    newRequest(UUID.randomUUID(), requestId, "mini"),
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    null,
                    List.of(),
                    null,
                    null,
                    false,
                    1
            );

            RedisRoutingStore.RouteEntry entry = new RedisRoutingStore.RouteEntry(context, "server-1A", System.currentTimeMillis());
            store.storeInFlightRoute(requestId, entry);

            assertThat(store.getInFlightRoute(requestId))
                    .isPresent()
                    .get()
                    .extracting(RedisRoutingStore.RouteEntry::getSlotId)
                    .isEqualTo("server-1A");

            assertThat(store.removeInFlightRoute(requestId))
                    .isPresent()
                    .get()
                    .extracting(RedisRoutingStore.RouteEntry::getContext)
                    .extracting(RedisRoutingStore.PlayerQueueEntry::getRequest)
                    .extracting(PlayerSlotRequest::getRequestId)
                    .isEqualTo(requestId);

            assertThat(store.getInFlightRoute(requestId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Party allocation state round-trips correctly")
    void partyAllocationPersistence() {
        try (RedisManager manager = newRedisManager()) {
            RedisRoutingStore store = newStore(manager);
            UUID partyId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            String reservationId = UUID.randomUUID().toString();

            PartyReservationToken token = new PartyReservationToken(
                    UUID.randomUUID().toString(),
                    partyId,
                    playerId,
                    "Member",
                    "server-1",
                    System.currentTimeMillis() + 30_000
            );

            PartyReservationSnapshot snapshot = new PartyReservationSnapshot(reservationId, partyId);
            snapshot.setFamilyId("mini");
            snapshot.setVariantId("rush");
            snapshot.setTargetServerId("server-1");
            snapshot.setTokens(Map.of(playerId, token));

            RedisRoutingStore.PartyAllocationEntry allocation = new RedisRoutingStore.PartyAllocationEntry(
                    snapshot,
                    reservationId,
                    "mini",
                    "rush",
                    "server-1A",
                    "A",
                    "server-1",
                    1,
                    0,
                    false,
                    System.currentTimeMillis(),
                    Set.of(playerId),
                    Set.of(),
                    Map.of()
            );

            store.savePartyAllocation(reservationId, allocation);

            assertThat(store.getPartyAllocation(reservationId))
                    .isPresent()
                    .get()
                    .extracting(RedisRoutingStore.PartyAllocationEntry::getSlotId)
                    .isEqualTo("server-1A");

            assertThat(store.removePartyAllocation(reservationId))
                    .isPresent()
                    .get()
                    .extracting(RedisRoutingStore.PartyAllocationEntry::getReservationId)
                    .isEqualTo(reservationId);

            assertThat(store.getPartyAllocation(reservationId)).isEmpty();
        }
    }

    @Test
    @DisplayName("Active slot tracking removes players when slot is cleared")
    void activeSlotTracking() {
        try (RedisManager manager = newRedisManager()) {
            RedisRoutingStore store = newStore(manager);
            UUID playerId = UUID.randomUUID();

            assertThat(store.setActiveSlot(playerId, "slot-one")).isEmpty();
            assertThat(store.setActiveSlot(playerId, "slot-two")).contains("slot-one");

            assertThat(store.removeActivePlayersForSlot("slot-two"))
                    .containsExactly(playerId);
            assertThat(store.getActiveSlot(playerId)).isEmpty();
        }
    }
}
