package sh.harold.fulcrum.registry.social;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.friends.FriendBlockScope;
import sh.harold.fulcrum.api.friends.FriendMutationRequest;
import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendSnapshot;
import sh.harold.fulcrum.registry.redis.RedisIntegrationTestSupport;
import sh.harold.fulcrum.registry.redis.RedisManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FriendGraphServiceIntegrationTest extends RedisIntegrationTestSupport {

    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.12");

    private FriendGraphService service;
    private FriendInviteStore inviteStore;
    private RedisManager redisManager;
    private DataAPI dataAPI;

    @BeforeEach
    void setUp() {
        redisManager = newRedisManager();
        MongoConnectionAdapter adapter = new MongoConnectionAdapter(
                MONGO.getConnectionString(), "friend_test_" + UUID.randomUUID());
        dataAPI = DataAPI.create(adapter);
        FriendSnapshotStore snapshotStore = new FriendSnapshotStore(dataAPI);
        inviteStore = new FriendInviteStore(redisManager);
        service = new FriendGraphService(snapshotStore, inviteStore, redisManager, java.util.logging.Logger.getLogger("friend-test"));
    }

    @AfterEach
    void tearDown() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
        }
        try {
            redisManager.close();
        } catch (Exception ignored) {
        }
        if (dataAPI != null) {
            dataAPI.shutdown();
        }
    }

    @Test
    void inviteAcceptanceCreatesMutualFriendship() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        FriendMutationRequest invite = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .metadata(Map.of("actorName", "Alpha"))
                .build();
        assertThat(service.apply(invite).result().success()).isTrue();

        List<FriendInviteStore.PendingInvite> pending = inviteStore.listPendingInvites(target);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).actorId()).isEqualTo(actor);

        FriendMutationRequest accept = FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(target)
                .target(actor)
                .build();
        assertThat(service.apply(accept).result().success()).isTrue();

        FriendSnapshot actorSnapshot = service.getSnapshot(actor, true);
        FriendSnapshot targetSnapshot = service.getSnapshot(target, true);
        assertThat(actorSnapshot.friends()).containsExactly(target);
        assertThat(targetSnapshot.friends()).containsExactly(actor);
        assertThat(inviteStore.listPendingInvites(target)).isEmpty();
    }

    @Test
    void blockAndUnblockFlowUpdatesSnapshots() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        establishFriendship(actor, target);

        FriendMutationRequest block = FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(actor)
                .target(target)
                .scope(FriendBlockScope.GLOBAL)
                .reason("test")
                .build();
        assertThat(service.apply(block).result().success()).isTrue();

        FriendSnapshot actorSnapshot = service.getSnapshot(actor, true);
        FriendSnapshot targetSnapshot = service.getSnapshot(target, true);

        assertThat(actorSnapshot.friends()).isEmpty();
        assertThat(actorSnapshot.blockedOut(FriendBlockScope.GLOBAL)).contains(target);
        assertThat(targetSnapshot.blockedIn(FriendBlockScope.GLOBAL)).contains(actor);

        FriendMutationRequest unblock = FriendMutationRequest.builder(FriendMutationType.UNBLOCK)
                .actor(actor)
                .target(target)
                .scope(FriendBlockScope.GLOBAL)
                .build();
        assertThat(service.apply(unblock).result().success()).isTrue();

        FriendSnapshot unblockedActor = service.getSnapshot(actor, true);
        FriendSnapshot unblockedTarget = service.getSnapshot(target, true);
        assertThat(unblockedActor.blockedOut(FriendBlockScope.GLOBAL)).isEmpty();
        assertThat(unblockedTarget.blockedIn(FriendBlockScope.GLOBAL)).isEmpty();
    }

    private void establishFriendship(UUID first, UUID second) {
        FriendMutationRequest send = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(first)
                .target(second)
                .build();
        service.apply(send);
        FriendMutationRequest accept = FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(second)
                .target(first)
                .build();
        service.apply(accept);
    }
}
