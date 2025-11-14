package sh.harold.fulcrum.registry.social;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.embedded.RedisServer;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.friends.FriendMutationRequest;
import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendSnapshot;
import sh.harold.fulcrum.registry.redis.RedisConfiguration;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FriendGraphServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fulcrum")
            .withUsername("fulcrum")
            .withPassword("password");

    private static RedisServer redisServer;
    private static RedisManager redisManager;
    private static FriendGraphService service;
    private static FriendGraphRepository repository;
    private static PostgresConnectionAdapter adapter;

    @BeforeAll
    static void setup() throws Exception {
        adapter = new PostgresConnectionAdapter(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                POSTGRES.getDatabaseName()
        );
        repository = new FriendGraphRepository(adapter, java.util.logging.Logger.getLogger(FriendGraphServiceTest.class.getName()));
        repository.ensureSchema(FriendGraphServiceTest.class.getClassLoader());

        int redisPort = randomPort();
        redisServer = RedisServer.builder()
                .port(redisPort)
                .setting("maxmemory 64m")
                .build();
        redisServer.start();
        redisManager = new RedisManager(new RedisConfiguration("127.0.0.1", redisPort, ""));
        service = new FriendGraphService(repository, redisManager, java.util.logging.Logger.getLogger(FriendGraphServiceTest.class.getName()));
    }

    @AfterAll
    static void teardown() {
        if (redisManager != null) {
            redisManager.close();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private static int randomPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void cleanState() throws Exception {
        try (Connection connection = adapter.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE social_friend_edges CASCADE");
            statement.execute("TRUNCATE social_friend_blocks CASCADE");
        }
        redisManager.sync().flushdb();
    }

    @Test
    void inviteCachesSnapshotsAndProducesRelationEvent() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        FriendGraphService.FriendOperationContext context = service.apply(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .metadata(Map.of("origin", "lobby"))
                .build());

        assertThat(context.result().success()).isTrue();
        assertThat(context.relationEvent()).isNotNull();
        assertThat(redisManager.sync().get(sh.harold.fulcrum.api.friends.FriendRedisKeys.snapshotKey(actor))).isNotBlank();
        assertThat(redisManager.sync().get(sh.harold.fulcrum.api.friends.FriendRedisKeys.snapshotKey(target))).isNotBlank();
    }

    @Test
    void blockWithExpiryIsPurged() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        establishFriendship(actor, target);

        service.apply(FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(actor)
                .target(target)
                .scope(FriendBlockScope.GLOBAL)
                .expiresAt(Instant.now().minusSeconds(5))
                .build());

        List<sh.harold.fulcrum.api.messagebus.messages.social.FriendBlockEventMessage> events = service.purgeExpiredBlocks();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).isActive()).isFalse();
    }

    @Test
    void snapshotReadsFromRedisWhenCached() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        FriendGraphService.FriendOperationContext context = service.apply(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .build());
        assertThat(context.result().success()).isTrue();

        FriendSnapshot snapshot = service.getSnapshot(actor, false);
        assertThat(snapshot.outgoingRequests()).containsExactly(target);
    }

    private void establishFriendship(UUID a, UUID b) {
        service.apply(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(a)
                .target(b)
                .build());
        service.apply(FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(b)
                .target(a)
                .build());
    }
}
