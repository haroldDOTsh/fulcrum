package sh.harold.fulcrum.fundamentals.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.chat.command.ChatCommands;
import sh.harold.fulcrum.fundamentals.chat.command.DisableMeCommand;
import sh.harold.fulcrum.fundamentals.punishment.RuntimePunishmentFeature;
import sh.harold.fulcrum.lifecycle.CommandRegistrar;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChatChannelFeature implements PluginFeature, Listener {
    private JavaPlugin plugin;
    private Logger logger;
    private ChatChannelServiceImpl service;
    private ChatEmojiService chatEmojiService;
    private MessageBus messageBus;
    private MessageHandler chatHandler;
    private MessageHandler partyHandler;

    @Override
    public int getPriority() {
        // After chat formatting (60) so prefixes can reuse it
        return 65;
    }

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.messageBus = container.getOptional(MessageBus.class).orElse(null);
        ChatFormatService chatFormatService = container.getOptional(ChatFormatService.class).orElse(null);
        RankService rankService = container.getOptional(RankService.class).orElse(null);
        LettuceRedisOperations redis = container.getOptional(LettuceRedisOperations.class).orElse(null);
        java.util.function.Supplier<MinigameEngine> engineSupplier = () -> container.getOptional(MinigameEngine.class)
                .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                        ? ServiceLocatorImpl.getInstance().findService(MinigameEngine.class).orElse(null)
                        : null);

        if (engineSupplier.get() == null) {
            logger.warning("MinigameEngine unavailable; chat isolation will fall back to global delivery.");
        }

        RuntimePunishmentFeature.RuntimePunishmentManager punishmentManager =
                container.getOptional(RuntimePunishmentFeature.RuntimePunishmentManager.class)
                        .orElseGet(() -> ServiceLocatorImpl.getInstance() != null
                                ? ServiceLocatorImpl.getInstance().findService(RuntimePunishmentFeature.RuntimePunishmentManager.class).orElse(null)
                                : null);

        this.chatEmojiService = new ChatEmojiServiceImpl(rankService);

        container.register(ChatEmojiService.class, this.chatEmojiService);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(ChatEmojiService.class, this.chatEmojiService));

        this.service = new ChatChannelServiceImpl(plugin, messageBus, chatFormatService, rankService, redis, engineSupplier, punishmentManager, this.chatEmojiService);

        container.register(ChatChannelService.class, service);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(ChatChannelService.class, service));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        subscribeMessageBus();

        ChatCommands commands = new ChatCommands(service);
        CommandRegistrar.register(commands.buildRoot());
        CommandRegistrar.register(commands.buildPartySend());
        CommandRegistrar.register(commands.buildStaffSend());
        var disabledMe = new DisableMeCommand().build();
        CommandRegistrar.register(disabledMe);
        CommandRegistrar.mutate(registrar -> {
            var dispatcher = registrar.getDispatcher();
            var minecraftRoot = dispatcher.getRoot().getChild("minecraft");
            if (minecraftRoot != null) {
                minecraftRoot.addChild(new DisableMeCommand().build());
            }
        });

        // Initialize existing players (unlikely during enable but safe)
        plugin.getServer().getOnlinePlayers().forEach(service::handlePlayerJoin);
    }

    @Override
    public void shutdown() {
        unsubscribeMessageBus();
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(ChatChannelService.class));
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(ChatEmojiService.class));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        service.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        event.getCommands().remove("me");
        event.getCommands().remove("minecraft:me");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        service.handleAsyncChat(event);
    }

    private void subscribeMessageBus() {
        if (messageBus == null) {
            logger.warning("MessageBus unavailable; chat channels will not forward across the network.");
            return;
        }

        chatHandler = this::handleChatMessage;
        partyHandler = this::handlePartyUpdate;

        messageBus.subscribe(ChannelConstants.CHAT_CHANNEL_MESSAGE, chatHandler);
        messageBus.subscribe(ChannelConstants.PARTY_UPDATE, partyHandler);
    }

    private void unsubscribeMessageBus() {
        if (messageBus == null) {
            return;
        }
        if (chatHandler != null) {
            messageBus.unsubscribe(ChannelConstants.CHAT_CHANNEL_MESSAGE, chatHandler);
        }
        if (partyHandler != null) {
            messageBus.unsubscribe(ChannelConstants.PARTY_UPDATE, partyHandler);
        }
    }

    private void handleChatMessage(MessageEnvelope envelope) {
        try {
            ChatChannelMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), ChatChannelMessage.class);
            if (message == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> service.handleIncomingMessage(message));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process chat channel message", ex);
        }
    }

    private void handlePartyUpdate(MessageEnvelope envelope) {
        try {
            PartyUpdateMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), PartyUpdateMessage.class);
            if (message == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> service.handlePartyUpdate(message));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process party update message", ex);
        }
    }
}
