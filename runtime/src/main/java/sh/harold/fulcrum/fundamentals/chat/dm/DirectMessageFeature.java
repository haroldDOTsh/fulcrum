package sh.harold.fulcrum.fundamentals.chat.dm;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.social.DirectMessageEnvelope;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class DirectMessageFeature implements PluginFeature, Listener {
    private JavaPlugin plugin;
    private DirectMessageServiceImpl service;
    private MessageBus messageBus;
    private MessageHandler handler;
    private ExecutorService executor;
    private DependencyContainer container;

    @Override
    public int getPriority() {
        return 265; // after chat channel feature
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.container = container;

        messageBus = container.getOptional(MessageBus.class).orElse(null);
        LettuceRedisOperations redis = container.getOptional(LettuceRedisOperations.class).orElse(null);
        ChatChannelService chatChannelService = container.getOptional(ChatChannelService.class)
                .orElseThrow(() -> new IllegalStateException("ChatChannelService unavailable for DM feature"));
        ChatFormatService chatFormatService = container.getOptional(ChatFormatService.class).orElse(null);
        ChatEmojiService chatEmojiService = container.getOptional(ChatEmojiService.class).orElse(null);
        RankService rankService = container.getOptional(RankService.class).orElse(null);

        executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), new NamedThreadFactory());
        service = new DirectMessageServiceImpl(plugin, messageBus, redis, chatChannelService, chatFormatService, chatEmojiService, rankService, executor);

        container.register(DirectMessageService.class, service);
        Optional.ofNullable(ServiceLocatorImpl.getInstance()).ifPresent(locator -> locator.registerService(DirectMessageService.class, service));

        chatChannelService.registerDirectMessageBridge(service);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        subscribeBus();

        DirectMessageCommands commands = new DirectMessageCommands(service);
        CommandRegistrar.register(commands.buildMessageCommand("msg"));
        CommandRegistrar.register(commands.buildMessageCommand("message"));
        CommandRegistrar.register(commands.buildReplyCommand("r"));
        CommandRegistrar.register(commands.buildReplyCommand("reply"));

        plugin.getServer().getOnlinePlayers().forEach(service::handlePlayerJoin);
    }

    @Override
    public void shutdown() {
        unsubscribeBus();
        Optional.ofNullable(ServiceLocatorImpl.getInstance()).ifPresent(locator -> locator.unregisterService(DirectMessageService.class));
        if (container != null) {
            container.unregister(DirectMessageService.class);
        }
        if (service != null) {
            service.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        service.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    private void subscribeBus() {
        if (messageBus == null) {
            plugin.getLogger().warning("MessageBus unavailable; direct messages will be local-only.");
            return;
        }
        handler = envelope -> handleEnvelope(envelope);
        messageBus.subscribe(ChannelConstants.SOCIAL_DIRECT_MESSAGE, handler);
    }

    private void unsubscribeBus() {
        if (messageBus != null && handler != null) {
            messageBus.unsubscribe(ChannelConstants.SOCIAL_DIRECT_MESSAGE, handler);
        }
    }

    private void handleEnvelope(MessageEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        try {
            DirectMessageEnvelope payload = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), DirectMessageEnvelope.class);
            if (payload == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> service.deliverIncoming(payload));
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to handle direct message payload: " + ex.getMessage());
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private int counter = 0;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fulcrum-dm-" + counter++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
