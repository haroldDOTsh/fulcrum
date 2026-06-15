package sh.harold.fulcrum.registry.authority;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.CachedAuthorityCommandPort;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.messagebus.adapter.MessageBusConnectionConfig;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ValkeyAuthorityCommandResultCacheTest {
    private static final DockerImageName VALKEY_IMAGE = DockerImageName.parse("valkey/valkey:7.2-alpine");

    @Test
    void declaresValkeyStoreOverRedisCompatibleWireProtocol() {
        assertThat(ValkeyAuthorityCommandResultCache.STORE_NAME).isEqualTo("valkey");
        assertThat(ValkeyAuthorityCommandResultCache.WIRE_PROTOCOL).isEqualTo("redis-compatible");
    }

    @Test
    void cacheKeysLiveInValkeyAuthorityNamespace() {
        assertThat(ValkeyAuthorityCommandResultCache.cacheKey("GRANT_RANK:player:1000"))
            .startsWith("fulcrum:authority:valkey:idempotency-result:")
            .doesNotContain("GRANT_RANK")
            .doesNotContain("player:1000");
    }

    @Test
    void writeScriptPreservesDedupeAndTtlSemantics() {
        assertThat(ValkeyAuthorityCommandResultCache.writeScript())
            .contains(
                "redis.call('HGET', KEYS[1], 'commandFingerprint')",
                "existing ~= ARGV[1]",
                "redis.call('HSET', KEYS[1]",
                "redis.call('PEXPIRE', KEYS[1], ARGV[10])"
            );
    }

    @SuppressWarnings("deprecation")
    @Test
    void redisNamedAdapterRemainsCompatibilityAlias() {
        assertThat(RedisAuthorityCommandResultCache.class)
            .isAssignableTo(ValkeyAuthorityCommandResultCache.class);
    }

    @Tag("live-substrate")
    @Test
    void liveValkeyPreservesIdempotencyResultAndRejectsFingerprintDrift() {
        try (GenericContainer<?> valkey = startValkey();
             ValkeyAuthorityCommandResultCache cache = new ValkeyAuthorityCommandResultCache(
                 MessageBusConnectionConfig.builder()
                     .type(MessageBusConnectionConfig.MessageBusType.REDIS)
                     .host(valkey.getHost())
                     .port(valkey.getMappedPort(6379))
                     .connectionTimeout(Duration.ofSeconds(5))
                     .build(),
                 Duration.ofSeconds(30)
             )) {
            String idempotencyKey = "rank-grant:" + UUID.randomUUID();
            DataAuthority.CommandResult firstResult = new DataAuthority.CommandResult(
                UUID.randomUUID(),
                true,
                7L,
                DataAuthority.RejectionReason.NONE,
                "accepted"
            );
            CachedAuthorityCommandPort.CachedCommandResult first =
                new CachedAuthorityCommandPort.CachedCommandResult(
                    idempotencyKey,
                    "fingerprint-a",
                    AuthorityCommandManifest.fingerprint(),
                    firstResult
                );
            CachedAuthorityCommandPort.CachedCommandResult drift =
                new CachedAuthorityCommandPort.CachedCommandResult(
                    idempotencyKey,
                    "fingerprint-b",
                    AuthorityCommandManifest.fingerprint(),
                    new DataAuthority.CommandResult(
                        UUID.randomUUID(),
                        true,
                        8L,
                        DataAuthority.RejectionReason.NONE,
                        "accepted-drift"
                    )
                );

            cache.write(first);
            cache.write(drift);

            assertThat(cache.read(idempotencyKey)).get().satisfies(cached -> {
                assertThat(cached.commandFingerprint()).isEqualTo("fingerprint-a");
                assertThat(cached.result().commandId()).isEqualTo(firstResult.commandId());
                assertThat(cached.result().revision()).isEqualTo(7L);
            });
        }
    }

    private static GenericContainer<?> startValkey() {
        GenericContainer<?> container = new GenericContainer<>(VALKEY_IMAGE).withExposedPorts(6379);
        try {
            container.start();
            return container;
        } catch (RuntimeException exception) {
            unavailableLiveSubstrate("Valkey", exception);
            throw exception;
        }
    }

    private static void unavailableLiveSubstrate(String substrate, RuntimeException exception) {
        String message = "Live " + substrate + " proof requires Docker/Testcontainers; startup failed: "
            + exception.getMessage();
        if (liveSubstratesRequired()) {
            throw new IllegalStateException(message, exception);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean liveSubstratesRequired() {
        return Boolean.getBoolean("fulcrum.test.substrates.requireLive")
            || Boolean.parseBoolean(System.getenv().getOrDefault(
                "FULCRUM_TEST_SUBSTRATES_REQUIRE_LIVE",
                "false"
            ));
    }
}
