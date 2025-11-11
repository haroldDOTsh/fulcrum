package sh.harold.fulcrum.common.cooldown;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCooldownRegistryTest {

    private static final Duration SHORT = Duration.ofMillis(30);

    private InMemoryCooldownRegistry registry;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        registry = new InMemoryCooldownRegistry();
        playerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void acquireGrantsWhenIdle() {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);

        CooldownAcquisition result = registry.acquire(key, CooldownSpec.rejecting(SHORT))
                .toCompletableFuture()
                .join();

        assertThat(result).isInstanceOf(CooldownAcquisition.Accepted.class);
        assertThat(registry.trackedCount()).isEqualTo(1);
    }

    @Test
    void acquireRejectsWhileActive() {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);
        registry.acquire(key, CooldownSpec.rejecting(SHORT)).toCompletableFuture().join();

        CooldownAcquisition second = registry.acquire(key, CooldownSpec.rejecting(SHORT))
                .toCompletableFuture()
                .join();

        assertThat(second).isInstanceOf(CooldownAcquisition.Rejected.class);
    }

    @Test
    void clearAllowsReacquire() {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);
        registry.acquire(key, CooldownSpec.rejecting(SHORT)).toCompletableFuture().join();
        registry.clear(key);

        CooldownAcquisition result = registry.acquire(key, CooldownSpec.rejecting(SHORT))
                .toCompletableFuture()
                .join();

        assertThat(result).isInstanceOf(CooldownAcquisition.Accepted.class);
    }

    @Test
    void extendPolicyAcceptsWhileActive() {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);
        registry.acquire(key, CooldownSpec.extending(SHORT)).toCompletableFuture().join();

        CooldownAcquisition second = registry.acquire(key, CooldownSpec.extending(SHORT))
                .toCompletableFuture()
                .join();

        assertThat(second).isInstanceOf(CooldownAcquisition.Accepted.class);
        assertThat(registry.trackedCount()).isEqualTo(1);
    }

    @Test
    void remainingReturnsDurationWhenActive() {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);
        registry.acquire(key, CooldownSpec.rejecting(SHORT)).toCompletableFuture().join();

        assertThat(registry.remaining(key)).isPresent();
    }

    @Test
    void linkSharesUnderlyingCooldown() {
        UUID npcId = UUID.randomUUID();
        CooldownKey primary = CooldownKeys.npcInteraction(playerId, npcId);
        CooldownKey alias = CooldownKeys.playerScoped("conversation", "tutorial", playerId);

        registry.link(primary, alias);

        registry.acquire(primary, CooldownSpec.rejecting(SHORT)).toCompletableFuture().join();
        CooldownAcquisition result = registry.acquire(alias, CooldownSpec.rejecting(SHORT))
                .toCompletableFuture()
                .join();

        assertThat(result).isInstanceOf(CooldownAcquisition.Rejected.class);
    }

    @Test
    void reaperRemovesExpiredEntries() throws InterruptedException {
        CooldownKey key = CooldownKeys.playerScoped("test", "demo", playerId);
        registry.acquire(key, CooldownSpec.rejecting(Duration.ofMillis(10))).toCompletableFuture().join();

        waitFor(() -> registry.trackedCount() == 0, Duration.ofSeconds(1));

        assertThat(registry.trackedCount()).isZero();
    }

    private void waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        // best effort
    }
}
