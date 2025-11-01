package sh.harold.fulcrum.registry.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdAllocatorIntegrationTest extends RedisIntegrationTestSupport {

    private IdAllocator newAllocator(RedisManager manager) {
        manager.sync().flushall();
        IdAllocator allocator = new IdAllocator(true);
        allocator.initialize(manager);
        return allocator;
    }

    @Test
    @DisplayName("Server IDs allocate sequentially and reuse recycled values per type")
    void allocateServerIds() {
        try (RedisManager manager = newRedisManager()) {
            IdAllocator allocator = newAllocator(manager);

            String mini1 = allocator.allocateServerId("mini");
            String mini2 = allocator.allocateServerId("mini");
            String mega1 = allocator.allocateServerId("mega");

            assertThat(mini1).isEqualTo("mini1");
            assertThat(mini2).isEqualTo("mini2");
            assertThat(mega1).isEqualTo("mega1");

            allocator.releaseServerId(mini1);
            String mini3 = allocator.allocateServerId("mini");

            assertThat(mini3).isEqualTo("mini1");
        }
    }

    @Test
    @DisplayName("Claiming a released server ID removes it from recycle pool")
    void claimServerIdRemovesRecycleEntry() {
        try (RedisManager manager = newRedisManager()) {
            IdAllocator allocator = newAllocator(manager);

            String serverId = allocator.allocateServerId("mini");
            allocator.releaseServerId(serverId);
            allocator.claimServerId(serverId);

            String next = allocator.allocateServerId("mini");
            assertThat(next).isEqualTo("mini2");
        }
    }

    @Test
    @DisplayName("Slot allocation honours suffix limits and recycles released letters")
    void allocateSlotSuffixes() {
        try (RedisManager manager = newRedisManager()) {
            IdAllocator allocator = newAllocator(manager);

            String base = allocator.allocateServerId("mini");
            String slotA = allocator.allocateSlotId(base);
            String slotB = allocator.allocateSlotId(base);

            assertThat(slotA).isEqualTo(base + "A");
            assertThat(slotB).isEqualTo(base + "B");

            allocator.releaseServerId(slotA);
            String slotC = allocator.allocateSlotId(base);

            assertThat(slotC).isEqualTo(base + "A");
        }
    }

    @Test
    @DisplayName("Proxy IDs allocate sequentially and recycle explicitly released IDs")
    void allocateProxyIds() {
        try (RedisManager manager = newRedisManager()) {
            IdAllocator allocator = newAllocator(manager);

            String proxy1 = allocator.allocateProxyId();
            String proxy2 = allocator.allocateProxyId();

            assertThat(proxy1).isEqualTo("fulcrum-proxy-1");
            assertThat(proxy2).isEqualTo("fulcrum-proxy-2");

            allocator.releaseProxyIdExplicit(proxy1, true);
            String proxy3 = allocator.allocateProxyId();

            assertThat(proxy3).isEqualTo("fulcrum-proxy-1");
        }
    }

    @Test
    @DisplayName("Slot allocation fails after exceeding suffix limit")
    void slotLimitEnforced() {
        try (RedisManager manager = newRedisManager()) {
            IdAllocator allocator = newAllocator(manager);
            String base = allocator.allocateServerId("mini");

            for (int i = 0; i < 26; i++) {
                allocator.allocateSlotId(base);
            }

            assertThatThrownBy(() -> allocator.allocateSlotId(base))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Maximum slots");
        }
    }
}
