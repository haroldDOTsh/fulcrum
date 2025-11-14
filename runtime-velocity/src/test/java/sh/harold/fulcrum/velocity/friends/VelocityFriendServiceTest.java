package sh.harold.fulcrum.velocity.friends;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.embedded.RedisServer;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendMutationCommandMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendMutationResponseMessage;
import sh.harold.fulcrum.api.messagebus.messages.social.FriendRelationEventMessage;
import sh.harold.fulcrum.velocity.config.RedisConfig;
import sh.harold.fulcrum.velocity.session.LettuceSessionRedisClient;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityFriendServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityFriendServiceTest.class);
    private static RedisServer redisServer;
    private static int redisPort;
    private VelocityFriendService service;
    private LettuceSessionRedisClient redisClient;
    private FakeMessageBus messageBus;

    @BeforeAll
    static void startRedis() throws Exception {
        redisPort = randomPort();
        redisServer = RedisServer.builder().port(redisPort).setting("maxmemory 64m").build();
        redisServer.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    private static Map<FriendBlockScope, Set<UUID>> emptyBlocks() {
        EnumMap<FriendBlockScope, Set<UUID>> blocks = new EnumMap<>(FriendBlockScope.class);
        for (FriendBlockScope scope : FriendBlockScope.values()) {
            blocks.put(scope, Set.of());
        }
        return blocks;
    }

    private static int randomPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setup() {
        messageBus = new FakeMessageBus();
        RedisConfig redisConfig = RedisConfig.builder().host("127.0.0.1").port(redisPort).build();
        redisClient = new LettuceSessionRedisClient(redisConfig, LOGGER);
        assertThat(redisClient.isAvailable()).isTrue();
        service = new VelocityFriendService(messageBus, redisClient, LOGGER);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void executeCompletesWhenResponseArrives() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        FriendMutationRequest request = FriendMutationRequest.builder(FriendMutationType.INVITE_SEND)
                .actor(actor)
                .target(target)
                .build();

        CompletableFuture<FriendOperationResult> future = service.execute(request).toCompletableFuture();
        FriendMutationCommandMessage command = messageBus.lastCommand();
        assertThat(command).isNotNull();

        FriendSnapshot actorSnapshot = new FriendSnapshot(5L, Set.of(target), Set.of(), Set.of(), emptyBlocks(), emptyBlocks());
        FriendMutationResponseMessage response = new FriendMutationResponseMessage(
                command.getRequestId(), true, command.getMutationType(), actor, target, actorSnapshot, FriendSnapshot.empty(), null);

        messageBus.publish(ChannelConstants.SOCIAL_FRIEND_MUTATION_RESPONSE, response);

        FriendOperationResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();
        assertThat(service.getSnapshot(actor, false).toCompletableFuture().get()).isEqualTo(actorSnapshot);
    }

    @Test
    void relationEventReloadsSnapshotFromRedis() throws Exception {
        UUID actor = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();

        FriendSnapshot initial = new FriendSnapshot(9L, Set.of(), Set.of(), Set.of(), emptyBlocks(), emptyBlocks());
        redisClient.set(FriendRedisKeys.snapshotKey(actor), mapper.writeValueAsString(initial), 0);
        assertThat(redisClient.get(FriendRedisKeys.snapshotKey(actor))).isNotBlank();
        FriendSnapshot parsed = mapper.readValue(redisClient.get(FriendRedisKeys.snapshotKey(actor)), FriendSnapshot.class);
        assertThat(parsed.version()).isEqualTo(9L);

        FriendSnapshot first = service.getSnapshot(actor, true).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(first.version()).isEqualTo(9L);

        FriendSnapshot updated = new FriendSnapshot(15L, Set.of(), Set.of(), Set.of(), emptyBlocks(), emptyBlocks());
        redisClient.set(FriendRedisKeys.snapshotKey(actor), mapper.writeValueAsString(updated), 0);
        assertThat(redisClient.get(FriendRedisKeys.snapshotKey(actor))).contains("\"version\":15");

        FriendSnapshot stale = service.getSnapshot(actor, false).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(stale.version()).isEqualTo(9L);

        FriendRelationEventMessage event = new FriendRelationEventMessage();
        event.setOwnerId(actor);
        event.setPeerId(UUID.randomUUID());
        event.setOwnerVersion(15L);
        event.setPeerVersion(1L);
        event.setRelationVersion(15L);

        messageBus.publish(ChannelConstants.SOCIAL_FRIEND_UPDATES, event);

        FriendSnapshot refreshed = service.getSnapshot(actor, false).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(refreshed.version()).isEqualTo(15L);
    }

    private static final class FakeMessageBus implements MessageBus {
        private final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageHandler>> handlers = new ConcurrentHashMap<>();
        private final ObjectMapper mapper = new ObjectMapper();
        private volatile FriendMutationCommandMessage lastCommand;

        @Override
        public void broadcast(String type, Object payload) {
            publish(type, payload);
        }

        @Override
        public void send(String targetServerId, String type, Object payload) {
            if (payload instanceof FriendMutationCommandMessage command) {
                lastCommand = command;
            }
        }

        @Override
        public CompletableFuture<Object> request(String targetServerId, String type, Object payload, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribe(String type, MessageHandler handler) {
            handlers.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(handler);
        }

        @Override
        public void unsubscribe(String type, MessageHandler handler) {
            handlers.getOrDefault(type, new CopyOnWriteArrayList<>()).remove(handler);
        }

        @Override
        public void refreshServerIdentity() {
        }

        @Override
        public String currentServerId() {
            return "proxy-test";
        }

        public void publish(String type, Object payload) {
            var list = handlers.get(type);
            if (list == null || list.isEmpty()) {
                return;
            }
            MessageEnvelope envelope = new MessageEnvelope(
                    type,
                    "registry-test",
                    null,
                    UUID.randomUUID(),
                    System.currentTimeMillis(),
                    1,
                    mapper.valueToTree(payload)
            );
            list.forEach(handler -> handler.handle(envelope));
        }

        FriendMutationCommandMessage lastCommand() {
            return lastCommand;
        }
    }
}
