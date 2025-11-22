package sh.harold.fulcrum.registry.social;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.impl.mongodb.MongoConnectionAdapter;
import sh.harold.fulcrum.api.friends.FriendMutationRequest;
import sh.harold.fulcrum.api.friends.FriendMutationType;
import sh.harold.fulcrum.api.friends.FriendOperationResult;
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
                .metadata(Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, "Alpha"))
                .build();
        assertThat(service.apply(invite).result().success()).isTrue();

        List<FriendInviteStore.PendingInvite> pending = inviteStore.listPendingInvites(target);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).actorId()).isEqualTo(actor);

        FriendMutationRequest accept = FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(target)
                .target(actor)
                .metadata(Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, "Bravo"))
                .build();
        assertThat(service.apply(accept).result().success()).isTrue();

        FriendSnapshot actorSnapshot = service.getSnapshot(actor, true);
        FriendSnapshot targetSnapshot = service.getSnapshot(target, true);
        assertThat(actorSnapshot.friendIds()).containsExactly(target);
        assertThat(targetSnapshot.friendIds()).containsExactly(actor);
        assertThat(actorSnapshot.friends().get(target).nickname()).isNull();
        assertThat(targetSnapshot.friends().get(actor).nickname()).isNull();
        assertThat(actorSnapshot.friends().get(target).since()).isNotNull();
        assertThat(targetSnapshot.friends().get(actor).since()).isNotNull();
        assertThat(inviteStore.listPendingInvites(target)).isEmpty();
    }

    @Test
    void declineFailsWhenInviteMissing() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        FriendMutationRequest decline = FriendMutationRequest.builder(FriendMutationType.INVITE_DECLINE)
                .actor(target)
                .target(actor)
                .build();
        FriendOperationResult result = service.apply(decline).result();
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No pending invite to decline");
    }

    @Test
    void cancelFailsWhenInviteMissing() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        FriendMutationRequest cancel = FriendMutationRequest.builder(FriendMutationType.INVITE_CANCEL)
                .actor(actor)
                .target(target)
                .build();
        FriendOperationResult result = service.apply(cancel).result();
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("No pending invite to cancel");
    }

    @Test
    void blockAndUnblockFlowUpdatesSnapshots() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        establishFriendship(actor, target);

        FriendMutationRequest block = FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(actor)
                .target(target)
                .reason("test")
                .build();
        assertThat(service.apply(block).result().success()).isTrue();

        FriendSnapshot actorSnapshot = service.getSnapshot(actor, true);
        FriendSnapshot targetSnapshot = service.getSnapshot(target, true);

        assertThat(actorSnapshot.friendIds()).isEmpty();
        assertThat(actorSnapshot.ignoresOutIds()).contains(target);
        assertThat(targetSnapshot.ignoresInIds()).contains(actor);

        FriendMutationRequest unblock = FriendMutationRequest.builder(FriendMutationType.UNBLOCK)
                .actor(actor)
                .target(target)
                .build();
        assertThat(service.apply(unblock).result().success()).isTrue();

        FriendSnapshot unblockedActor = service.getSnapshot(actor, true);
        FriendSnapshot unblockedTarget = service.getSnapshot(target, true);
        assertThat(unblockedActor.ignoresOutIds()).isEmpty();
        assertThat(unblockedTarget.ignoresInIds()).isEmpty();
    }

    @Test
    void setNicknameStoresMetadataUnidirectionally() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        establishFriendship(actor, target);

        FriendMutationRequest setNickname = FriendMutationRequest.builder(FriendMutationType.SET_METADATA)
                .actor(actor)
                .target(target)
                .nickname("Buddy")
                .build();
        assertThat(service.apply(setNickname).result().success()).isTrue();

        FriendSnapshot actorSnapshot = service.getSnapshot(actor, true);
        FriendSnapshot targetSnapshot = service.getSnapshot(target, true);

        assertThat(actorSnapshot.metadata().get(target).nickname()).isEqualTo("Buddy");
        assertThat(targetSnapshot.metadata().get(actor)).isNull();

        FriendMutationRequest clearNickname = FriendMutationRequest.builder(FriendMutationType.SET_METADATA)
                .actor(actor)
                .target(target)
                .nickname("   ")
                .build();
        assertThat(service.apply(clearNickname).result().success()).isTrue();

        FriendSnapshot cleared = service.getSnapshot(actor, true);
        assertThat(cleared.metadata().get(target)).isNull();
    }

    @Test
    void ignoreBypassAllowsStaffInvites() {
        UUID staffActor = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        FriendMutationRequest block = FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(player)
                .target(staffActor)
                .reason("test-block")
                .build();
        assertThat(service.apply(block).result().success()).isTrue();

        FriendMutationRequest deniedInvite = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(staffActor)
                .target(player)
                .build();
        FriendOperationResult deniedResult = service.apply(deniedInvite).result();
        assertThat(deniedResult.success()).isFalse();

        FriendMutationRequest bypassInvite = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(staffActor)
                .target(player)
                .putMetadata(FriendMutationRequest.METADATA_IGNORE_BYPASS, true)
                .build();
        FriendOperationResult bypassResult = service.apply(bypassInvite).result();
        assertThat(bypassResult.success()).isTrue();
    }

    private void establishFriendship(UUID first, UUID second) {
        FriendMutationRequest send = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(first)
                .target(second)
                .metadata(Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, "First"))
                .build();
        service.apply(send);
        FriendMutationRequest accept = FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(second)
                .target(first)
                .metadata(Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, "Second"))
                .build();
        service.apply(accept);
    }
}
