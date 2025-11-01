package sh.harold.fulcrum.registry.slot.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSlotStoreIntegrationTest extends RedisIntegrationTestSupport {

    private RedisSlotStore newStore(RedisManager manager) {
        return new RedisSlotStore(manager);
    }

    private RegisteredServerData server(String id, Map<String, Integer> capacities) {
        RegisteredServerData data = new RegisteredServerData(id, "temp-" + id, "mini", "localhost", 25565, 100);
        data.updateSlotFamilyCapacities(capacities);
        return data;
    }

    @Test
    @DisplayName("Reserve and release family capacity atomically")
    void reserveAndReleaseCapacity() {
        try (RedisManager manager = newRedisManager()) {
            RedisSlotStore store = newStore(manager);
            RegisteredServerData server = server("mini1", Map.of("mini", 3));
            store.syncServer(server);

            int remaining = store.reserveFamilyCapacity("mini1", "mini");
            assertThat(remaining).isEqualTo(2);

            remaining = store.reserveFamilyCapacity("mini1", "mini");
            assertThat(remaining).isEqualTo(1);

            remaining = store.reserveFamilyCapacity("mini1", "mini");
            assertThat(remaining).isEqualTo(0);

            remaining = store.reserveFamilyCapacity("mini1", "mini");
            assertThat(remaining).isEqualTo(-1);

            int released = store.releaseFamilyCapacity("mini1", "mini");
            assertThat(released).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Store slot metadata and remove slot")
    void storeSlotMetadata() {
        try (RedisManager manager = newRedisManager()) {
            RedisSlotStore store = newStore(manager);
            RegisteredServerData server = server("mini2", Map.of("mini", 1));
            store.syncServer(server);

            LogicalSlotRecord slot = new LogicalSlotRecord("mini2A", "A", "mini2");
            slot.setGameType("pvp");
            slot.setMaxPlayers(16);
            slot.setOnlinePlayers(0);
            slot.replaceMetadata(Map.of("family", "mini"));

            store.storeSlot(server, slot, "mini");
            assertThat(manager.sync().exists("fulcrum:registry:slots:mini2A")).isEqualTo(1);

            store.removeSlot("mini2A", "mini");
            assertThat(manager.sync().exists("fulcrum:registry:slots:mini2A")).isZero();
        }
    }
}
