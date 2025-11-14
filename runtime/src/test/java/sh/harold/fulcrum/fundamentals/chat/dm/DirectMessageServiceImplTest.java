package sh.harold.fulcrum.fundamentals.chat.dm;

import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.social.DirectMessageEnvelope;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class DirectMessageServiceImplTest {

    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private MessageBus messageBus;
    private LettuceRedisOperations redis;
    private ChatChannelService chatChannelService;
    private ChatFormatService chatFormatService;
    private ChatEmojiService chatEmojiService;
    private Player sender;
    private Player target;
    private ExecutorService executor;
    private DirectMessageServiceImpl service;
    private Clock clock;
    private Map<String, String> redisStore;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        messageBus = mock(MessageBus.class);
        redis = mock(LettuceRedisOperations.class);
        chatChannelService = mock(ChatChannelService.class);
        chatFormatService = mock(ChatFormatService.class);
        chatEmojiService = mock(ChatEmojiService.class);
        sender = mock(Player.class);
        target = mock(Player.class);
        executor = new SameThreadExecutor();
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        redisStore = new java.util.concurrent.ConcurrentHashMap<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getPlayerExact("Target")).thenReturn(target);
        when(server.getPlayer(target.getUniqueId())).thenReturn(target);
        when(server.getPlayer(sender.getUniqueId())).thenReturn(sender);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(1);
            runnable.run();
            return mock(org.bukkit.scheduler.BukkitTask.class);
        });

        when(redis.isAvailable()).thenReturn(true);
        lenient().when(redis.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0)));
        lenient().doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redis).set(anyString(), anyString(), anyLong());
        lenient().when(redis.delete(anyString())).thenAnswer(invocation -> redisStore.remove(invocation.getArgument(0)) != null);
        lenient().when(redis.setIfAbsent(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (redisStore.containsKey(key)) {
                return false;
            }
            redisStore.put(key, invocation.getArgument(1));
            return true;
        });

        UUID senderId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(sender.getUniqueId()).thenReturn(senderId);
        when(sender.getName()).thenReturn("Sender");
        when(sender.isOnline()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Target");
        when(target.isOnline()).thenReturn(true);

        redisStore.put("fulcrum:player:" + targetId + ":state", "{}");

        when(chatEmojiService.apply(any(Player.class), any(Component.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        service = new DirectMessageServiceImpl(plugin, messageBus, redis, chatChannelService,
                chatFormatService, chatEmojiService, null, executor, clock);
        service.handlePlayerJoin(target);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        executor.shutdownNow();
    }

    @Test
    void sendMessagePublishesEnvelopeAndEchoes() {
        doNothing().when(messageBus).broadcast(eq(ChannelConstants.SOCIAL_DIRECT_MESSAGE), any());

        service.sendMessage(sender, "Target", "hello").toCompletableFuture().join();

        verify(messageBus).broadcast(eq(ChannelConstants.SOCIAL_DIRECT_MESSAGE), any(DirectMessageEnvelope.class));
        verify(sender, atLeastOnce()).sendMessage(any(Component.class));
    }

    @Test
    void openChannelToggles() {
        service.openChannel(sender, "Target").toCompletableFuture().join();
        DirectMessageResult result = service.openChannel(sender, "Target").toCompletableFuture().join();
        org.junit.jupiter.api.Assertions.assertEquals(DirectMessageResult.CHANNEL_CLOSED, result);
    }

    private static final class SameThreadExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
