package sh.harold.fulcrum.registry.coordination;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRegistryCoordinationStoreTest {

    @Test
    void enforcesCapacityUntilLeaseReleased() {
        InMemoryRegistryCoordinationStore store = new InMemoryRegistryCoordinationStore();

        var first = store.reserveCapacity("mini1", "duels", 1, Duration.ofSeconds(30), Map.of());
        var second = store.reserveCapacity("mini1", "duels", 1, Duration.ofSeconds(30), Map.of());

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());

        store.releaseCapacity(first.get());

        var third = store.reserveCapacity("mini1", "duels", 1, Duration.ofSeconds(30), Map.of());
        assertTrue(third.isPresent());
    }
}
