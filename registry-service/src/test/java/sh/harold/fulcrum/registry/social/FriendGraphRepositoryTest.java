package sh.harold.fulcrum.registry.social;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.friends.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FriendGraphRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fulcrum")
            .withUsername("fulcrum")
            .withPassword("password");

    private static FriendGraphRepository repository;
    private static PostgresConnectionAdapter adapter;

    @BeforeAll
    static void setup() {
        adapter = new PostgresConnectionAdapter(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                POSTGRES.getDatabaseName()
        );
        repository = new FriendGraphRepository(adapter, Logger.getLogger(FriendGraphRepositoryTest.class.getName()));
        repository.ensureSchema(FriendGraphRepositoryTest.class.getClassLoader());
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection connection = adapter.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE social_friend_edges CASCADE");
            statement.execute("TRUNCATE social_friend_blocks CASCADE");
        }
    }

    @Test
    void inviteCreatesMirrorEdges() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        FriendGraphMutationResult result = repository.applyMutation(
                FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                        .actor(actor)
                        .target(target)
                        .metadata(Map.of("server", "lobby"))
                        .build());

        assertThat(result.success()).isTrue();
        assertThat(result.actorState()).isEqualTo(FriendRelationState.INVITE_OUTGOING);
        assertThat(result.targetState()).isEqualTo(FriendRelationState.INVITE_INCOMING);

        FriendSnapshot actorSnapshot = repository.loadSnapshot(actor);
        FriendSnapshot targetSnapshot = repository.loadSnapshot(target);

        assertThat(actorSnapshot.outgoingRequests()).containsExactly(target);
        assertThat(targetSnapshot.incomingRequests()).containsExactly(actor);
    }

    @Test
    void acceptingInviteTransitionsToAcceptedState() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .build());

        FriendGraphMutationResult accept = repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(target)
                .target(actor)
                .build());

        assertThat(accept.success()).isTrue();
        assertThat(accept.actorState()).isEqualTo(FriendRelationState.ACCEPTED);

        FriendSnapshot actorSnapshot = repository.loadSnapshot(actor);
        FriendSnapshot targetSnapshot = repository.loadSnapshot(target);

        assertThat(actorSnapshot.friends()).containsExactly(target);
        assertThat(targetSnapshot.friends()).containsExactly(actor);
        assertThat(actorSnapshot.outgoingRequests()).isEmpty();
        assertThat(targetSnapshot.incomingRequests()).isEmpty();
    }

    @Test
    void reciprocalInviteBecomesFriendship() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(first)
                .target(second)
                .build());

        FriendGraphMutationResult reciprocal = repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(second)
                .target(first)
                .build());

        assertThat(reciprocal.success()).isTrue();
        assertThat(reciprocal.actorState()).isEqualTo(FriendRelationState.ACCEPTED);
        assertThat(reciprocal.targetState()).isEqualTo(FriendRelationState.ACCEPTED);

        FriendSnapshot firstSnapshot = repository.loadSnapshot(first);
        FriendSnapshot secondSnapshot = repository.loadSnapshot(second);
        assertThat(firstSnapshot.friends()).containsExactly(second);
        assertThat(secondSnapshot.friends()).containsExactly(first);
    }

    @Test
    void unfriendRemovesEdges() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        establishFriendship(actor, target);

        FriendGraphMutationResult unfriend = repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.UNFRIEND)
                .actor(actor)
                .target(target)
                .build());

        assertThat(unfriend.success()).isTrue();
        assertThat(repository.loadSnapshot(actor).friends()).isEmpty();
        assertThat(repository.loadSnapshot(target).friends()).isEmpty();
    }

    @Test
    void blockClearsFriendshipAndStoresBlock() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        establishFriendship(actor, target);

        FriendGraphMutationResult block = repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.BLOCK)
                .actor(actor)
                .target(target)
                .scope(FriendBlockScope.GLOBAL)
                .reason("testing")
                .build());

        assertThat(block.success()).isTrue();
        FriendSnapshot actorSnapshot = repository.loadSnapshot(actor);
        assertThat(actorSnapshot.friends()).isEmpty();
        assertThat(actorSnapshot.blockedOut(FriendBlockScope.GLOBAL)).containsExactly(target);
        assertThat(repository.loadSnapshot(target).blockedIn(FriendBlockScope.GLOBAL)).containsExactly(actor);
    }

    @Test
    void unblockRequiresExistingBlock() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        FriendGraphMutationResult result = repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.UNBLOCK)
                .actor(actor)
                .target(target)
                .scope(FriendBlockScope.GLOBAL)
                .build());
        assertThat(result.success()).isFalse();
    }

    private void establishFriendship(UUID a, UUID b) throws Exception {
        repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(a)
                .target(b)
                .build());
        repository.applyMutation(FriendMutationRequest.builder(FriendMutationType.INVITE_ACCEPT)
                .actor(b)
                .target(a)
                .build());
    }
}
