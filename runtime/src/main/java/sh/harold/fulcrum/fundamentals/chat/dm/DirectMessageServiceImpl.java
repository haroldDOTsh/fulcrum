package sh.harold.fulcrum.fundamentals.chat.dm;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.ChatEmojiService;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.social.DirectMessageEnvelope;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

final class DirectMessageServiceImpl implements DirectMessageService {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final Duration CHANNEL_TTL = Duration.ofMinutes(5);
    private static final long STATE_TTL_SECONDS = Math.max(5L, CHANNEL_TTL.toSeconds());
    private static final long RATE_LIMIT_MILLIS = 1500L;
    private static final long RATE_LIMIT_SECONDS = 2L;
    private static final String NAME_KEY_PREFIX = "social:dm:name:";
    private static final String STATE_KEY_PREFIX = "social:dm:";
    private static final String LAST_TARGET_SUFFIX = ":lastTarget";
    private static final String LAST_TARGET_NAME_SUFFIX = ":lastTargetName";
    private static final String OPEN_CHANNEL_SUFFIX = ":openChannel";
    private static final String OPEN_UPDATED_SUFFIX = ":openUpdated";
    private static final String LAST_MUTATION_SUFFIX = ":lastMutation";
    private static final String RATE_LIMIT_SUFFIX = ":cooldown";
    private static final String SESSION_STATE_PREFIX = "fulcrum:player:";
    private static final String SESSION_STATE_SUFFIX = ":state";

    private final JavaPlugin plugin;
    private final MessageBus messageBus;
    private final LettuceRedisOperations redis;
    private final ChatChannelService chatChannelService;
    private final ChatFormatService chatFormatService;
    private final ChatEmojiService chatEmojiService;
    private final RankService rankService;
    private final ExecutorService executor;
    private final Clock clock;
    private final Map<UUID, DirectMessageState> states = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> idToName = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameToId = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    DirectMessageServiceImpl(JavaPlugin plugin,
                             MessageBus messageBus,
                             LettuceRedisOperations redis,
                             ChatChannelService chatChannelService,
                             ChatFormatService chatFormatService,
                             ChatEmojiService chatEmojiService,
                             RankService rankService,
                             ExecutorService executor,
                             Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageBus = messageBus;
        this.redis = redis;
        this.chatChannelService = Objects.requireNonNull(chatChannelService, "chatChannelService");
        this.chatFormatService = chatFormatService;
        this.chatEmojiService = chatEmojiService;
        this.rankService = rankService;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNullElseGet(clock, Clock::systemUTC);
    }

    DirectMessageServiceImpl(JavaPlugin plugin,
                             MessageBus messageBus,
                             LettuceRedisOperations redis,
                             ChatChannelService chatChannelService,
                             ChatFormatService chatFormatService,
                             ChatEmojiService chatEmojiService,
                             ExecutorService executor) {
        this(plugin, messageBus, redis, chatChannelService, chatFormatService, chatEmojiService, null, executor, Clock.systemUTC());
    }

    DirectMessageServiceImpl(JavaPlugin plugin,
                             MessageBus messageBus,
                             LettuceRedisOperations redis,
                             ChatChannelService chatChannelService,
                             ChatFormatService chatFormatService,
                             ChatEmojiService chatEmojiService,
                             RankService rankService,
                             ExecutorService executor) {
        this(plugin, messageBus, redis, chatChannelService, chatFormatService, chatEmojiService, rankService, executor, Clock.systemUTC());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Override
    public CompletionStage<DirectMessageResult> sendMessage(Player sender, String target, String message) {
        Objects.requireNonNull(sender, "sender");
        if (message == null || message.isBlank()) {
            sendSync(sender, Component.text("You must include a message.", NamedTextColor.RED));
            return CompletableFuture.completedFuture(DirectMessageResult.MESSAGE_REQUIRED);
        }

        UUID senderId = sender.getUniqueId();
        String senderName = sender.getName();
        Component rendered = applyEmojis(sender, Component.text(message));
        String plain = PLAIN.serialize(rendered);

        return resolveTargetHandle(target)
                .thenCompose(handleOpt -> handleOpt
                        .map(handle -> dispatchMessage(sender, senderId, senderName, handle, rendered, plain))
                        .orElseGet(() -> {
                            sendSync(sender, Component.text("Player '" + target + "' is not available.", NamedTextColor.RED));
                            return CompletableFuture.completedFuture(DirectMessageResult.TARGET_NOT_FOUND);
                        }));
    }

    @Override
    public CompletionStage<DirectMessageResult> openChannel(Player sender, String target) {
        Objects.requireNonNull(sender, "sender");
        UUID senderId = sender.getUniqueId();
        return resolveTargetHandle(target)
                .thenCompose(handleOpt -> handleOpt
                        .map(handle -> {
                            if (senderId.equals(handle.id())) {
                                sendSync(sender, Component.text("You cannot open a conversation with yourself.", NamedTextColor.RED));
                                return CompletableFuture.completedFuture(DirectMessageResult.SELF_TARGET);
                            }

                            DirectMessageState current = states.get(senderId);
                            if (current != null && current.hasOpenChannel() && handle.id().equals(current.openTargetId())) {
                                closeOpenChannel(senderId, sender, Component.text("Closed private conversation with " + safeName(handle) + ".", NamedTextColor.YELLOW), true);
                                return CompletableFuture.completedFuture(DirectMessageResult.CHANNEL_CLOSED);
                            }

                            return ensureOnline(handle.id())
                                    .thenApply(online -> {
                                        if (!online) {
                                            sendSync(sender, Component.text(handle.name() + " is offline right now.", NamedTextColor.RED));
                                            return DirectMessageResult.RECIPIENT_OFFLINE;
                                        }
                                        Instant now = clock.instant();
                                        states.compute(senderId, (id, state) -> {
                                            DirectMessageState base = state != null ? state : DirectMessageState.empty();
                                            DirectMessageState withTarget = base.withLastTarget(handle.id(), handle.name(), now);
                                            return withTarget.withOpenChannel(handle.id(), channelId(handle.id()), now);
                                        });
                                        persistLastTarget(senderId, handle, now);
                                        persistOpenChannel(senderId, handle.id(), channelId(handle.id()), now);
                                        sendSync(sender, Component.text("You are now chatting privately with " + safeName(handle) + ".", NamedTextColor.GREEN));
                                        return DirectMessageResult.CHANNEL_OPENED;
                                    });
                        })
                        .orElseGet(() -> {
                            sendSync(sender, Component.text("Player '" + target + "' is not available.", NamedTextColor.RED));
                            return CompletableFuture.completedFuture(DirectMessageResult.TARGET_NOT_FOUND);
                        }));
    }

    @Override
    public CompletionStage<DirectMessageResult> reply(Player sender, String message) {
        Objects.requireNonNull(sender, "sender");
        UUID senderId = sender.getUniqueId();
        DirectMessageState state = states.get(senderId);
        if (state == null || state.lastTargetId() == null) {
            sendSync(sender, Component.text("No recent partner to reply to.", NamedTextColor.RED));
            return CompletableFuture.completedFuture(DirectMessageResult.REPLY_TARGET_MISSING);
        }
        TargetHandle handle = new TargetHandle(state.lastTargetId(), safeName(state.lastTargetId(), state.lastTargetName()));
        return sendMessage(sender, handle.name(), message);
    }

    @Override
    public void handlePlayerJoin(Player player) {
        if (player == null || shutdown.get()) {
            return;
        }
        cacheName(player.getUniqueId(), player.getName());
        if (!loadStateFromRedis(player.getUniqueId())) {
            states.putIfAbsent(player.getUniqueId(), DirectMessageState.empty());
        }
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }
        closeOpenChannel(playerId, null, null, false);
        states.remove(playerId);
        lastMessageAt.remove(playerId);
        removeName(playerId);
        clearRemoteState(playerId);
    }

    @Override
    public void deliverIncoming(DirectMessageEnvelope envelope) {
        if (envelope == null || envelope.getRecipientId() == null) {
            return;
        }
        UUID recipientId = envelope.getRecipientId();
        Player recipient = plugin.getServer().getPlayer(recipientId);
        if (recipient == null || !recipient.isOnline()) {
            return;
        }
        cacheName(envelope.getSenderId(), envelope.getSenderName());
        Component body = decodeComponent(envelope);
        TargetHandle senderHandle = new TargetHandle(envelope.getSenderId(), safeName(envelope.getSenderId(), envelope.getSenderName()));
        sendSync(recipient, formatIncoming(senderHandle, body));
        Instant now = clock.instant();
        states.compute(recipientId, (id, state) -> {
            DirectMessageState base = state != null ? state : DirectMessageState.empty();
            return base.withLastTarget(senderHandle.id(), senderHandle.name(), now);
        });
        persistLastTarget(recipientId, senderHandle, now);
    }

    @Override
    public void shutdown() {
        shutdown.set(true);
        states.clear();
        lastMessageAt.clear();
        nameToId.clear();
        idToName.clear();
    }

    @Override
    public boolean interceptChat(AsyncChatEvent event) {
        if (event == null) {
            return false;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        DirectMessageState state = states.get(playerId);
        if (state == null || !state.hasOpenChannel()) {
            return false;
        }
        if (isChannelExpired(state)) {
            closeOpenChannel(playerId, player, Component.text("Your private conversation expired.", NamedTextColor.YELLOW), true);
            return false;
        }

        UUID targetId = state.openTargetId();
        TargetHandle handle = new TargetHandle(targetId, safeName(targetId, state.lastTargetName()));
        Component processed = applyEmojis(player, event.message());
        String plain = PLAIN.serialize(processed);

        event.viewers().clear();
        event.message(Component.empty());
        dispatchMessage(player, playerId, player.getName(), handle, processed, plain);
        return true;
    }

    @Override
    public void handleChannelSwitch(UUID playerId) {
        if (playerId == null) {
            return;
        }
        closeOpenChannel(playerId, null, null, false);
    }

    private CompletionStage<DirectMessageResult> dispatchMessage(Player sender,
                                                                 UUID senderId,
                                                                 String senderName,
                                                                 TargetHandle handle,
                                                                 Component message,
                                                                 String plainText) {
        if (senderId.equals(handle.id())) {
            sendSync(sender, Component.text("You cannot message yourself.", NamedTextColor.RED));
            return CompletableFuture.completedFuture(DirectMessageResult.SELF_TARGET);
        }
        if (messageBus == null) {
            sendSync(sender, Component.text("Messaging service unavailable right now.", NamedTextColor.RED));
            return CompletableFuture.completedFuture(DirectMessageResult.INTERNAL_ERROR);
        }
        return ensureOnline(handle.id())
                .thenCompose(online -> {
                    if (!online) {
                        sendSync(sender, Component.text(handle.name() + " is offline right now.", NamedTextColor.RED));
                        closeOpenChannel(senderId, sender, null, false);
                        return CompletableFuture.completedFuture(DirectMessageResult.RECIPIENT_OFFLINE);
                    }
                    if (!checkRateLimit(senderId)) {
                        sendSync(sender, Component.text("You are messaging too quickly.", NamedTextColor.RED));
                        return CompletableFuture.completedFuture(DirectMessageResult.RATE_LIMITED);
                    }
                    Instant now = clock.instant();
                    states.compute(senderId, (id, state) -> {
                        DirectMessageState base = state != null ? state : DirectMessageState.empty();
                        return base.withLastTarget(handle.id(), handle.name(), now);
                    });
                    persistLastTarget(senderId, handle, now);
                    publishEnvelope(senderId, senderName, handle, message, plainText, now);
                    sendSync(sender, formatOutgoing(handle, message));
                    return CompletableFuture.completedFuture(DirectMessageResult.DELIVERED);
                });
    }

    private CompletionStage<Optional<TargetHandle>> resolveTargetHandle(String input) {
        if (input == null || input.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String trimmed = input.trim();
        UUID parsed = tryParseUuid(trimmed);
        if (parsed != null) {
            String name = safeName(parsed, idToName.get(parsed));
            return CompletableFuture.completedFuture(Optional.of(new TargetHandle(parsed, name)));
        }

        Player local = plugin.getServer().getPlayerExact(trimmed);
        if (local != null) {
            cacheName(local.getUniqueId(), local.getName());
            return CompletableFuture.completedFuture(Optional.of(new TargetHandle(local.getUniqueId(), local.getName())));
        }

        UUID cached = nameToId.get(trimmed.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(new TargetHandle(cached, safeName(cached, idToName.get(cached)))));
        }

        if (redis == null || !redis.isAvailable()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            String raw = redis.get(nameKey(trimmed));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                UUID id = UUID.fromString(raw.trim());
                String name = safeName(id, trimmed);
                cacheName(id, name);
                return Optional.of(new TargetHandle(id, name));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }, executor);
    }

    private boolean checkRateLimit(UUID playerId) {
        long now = System.currentTimeMillis();
        Long last = lastMessageAt.get(playerId);
        if (last != null && now - last < RATE_LIMIT_MILLIS) {
            return false;
        }
        if (redis != null && redis.isAvailable()) {
            boolean allowed = redis.setIfAbsent(rateKey(playerId), String.valueOf(now), RATE_LIMIT_SECONDS);
            if (!allowed) {
                return false;
            }
        }
        lastMessageAt.put(playerId, now);
        return true;
    }

    private CompletionStage<Boolean> ensureOnline(UUID playerId) {
        if (redis != null && redis.isAvailable()) {
            return CompletableFuture.supplyAsync(() -> redis.get(sessionStateKey(playerId)) != null, executor);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        runSync(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            future.complete(target != null && target.isOnline());
        });
        return future;
    }

    private void publishEnvelope(UUID senderId,
                                 String senderName,
                                 TargetHandle handle,
                                 Component message,
                                 String plain,
                                 Instant timestamp) {
        if (messageBus == null) {
            return;
        }
        DirectMessageEnvelope envelope = new DirectMessageEnvelope();
        envelope.setMessageId(UUID.randomUUID());
        envelope.setSenderId(senderId);
        envelope.setSenderName(senderName);
        envelope.setRecipientId(handle.id());
        envelope.setRecipientName(handle.name());
        envelope.setComponentJson(GSON.serialize(message));
        envelope.setPlainText(plain);
        envelope.setTimestamp(timestamp.toEpochMilli());
        try {
            messageBus.broadcast(ChannelConstants.SOCIAL_DIRECT_MESSAGE, envelope);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to publish direct message: " + ex.getMessage());
        }
    }

    private Component formatOutgoing(TargetHandle target, Component body) {
        Component header = Component.text("To ", NamedTextColor.LIGHT_PURPLE)
                .append(formatPlayerDisplay(target))
                .append(Component.text(": ", NamedTextColor.LIGHT_PURPLE));
        return header.append(body.color(NamedTextColor.GRAY));
    }

    private Component formatIncoming(TargetHandle sender, Component body) {
        Component header = Component.text("From ", NamedTextColor.LIGHT_PURPLE)
                .append(formatPlayerDisplay(sender))
                .append(Component.text(": ", NamedTextColor.LIGHT_PURPLE));
        return header.append(body.color(NamedTextColor.GRAY));
    }

    private Component formatPlayerDisplay(TargetHandle handle) {
        Rank rank = resolveRank(handle.id());
        NamedTextColor nameColor = rank != null ? rank.getNameColor() : NamedTextColor.AQUA;
        Component nameComponent = Component.text(safeName(handle), nameColor);
        if (rank == null || rank == Rank.DEFAULT) {
            return nameComponent;
        }
        String prefix = firstNonBlank(rank.getShortPrefix(), rank.getFullPrefix());
        if (prefix == null || prefix.isBlank()) {
            return nameComponent;
        }
        return Component.text(prefix + " ", nameColor).append(nameComponent);
    }

    private Component decodeComponent(DirectMessageEnvelope envelope) {
        if (envelope.getComponentJson() != null && !envelope.getComponentJson().isBlank()) {
            try {
                return GSON.deserialize(envelope.getComponentJson());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to decode direct message payload: " + ex.getMessage());
            }
        }
        return Component.text(Optional.ofNullable(envelope.getPlainText()).orElse(""), NamedTextColor.WHITE);
    }

    private void persistLastTarget(UUID playerId, TargetHandle handle, Instant now) {
        if (redis == null || !redis.isAvailable()) {
            return;
        }
        redis.set(stateKey(playerId, LAST_TARGET_SUFFIX), handle.id().toString(), STATE_TTL_SECONDS);
        redis.set(stateKey(playerId, LAST_TARGET_NAME_SUFFIX), safeName(handle), STATE_TTL_SECONDS);
        redis.set(stateKey(playerId, LAST_MUTATION_SUFFIX), String.valueOf(now.toEpochMilli()), STATE_TTL_SECONDS);
    }

    private void persistOpenChannel(UUID playerId, UUID targetId, String channelId, Instant now) {
        if (redis == null || !redis.isAvailable()) {
            return;
        }
        redis.set(stateKey(playerId, OPEN_CHANNEL_SUFFIX), channelId, STATE_TTL_SECONDS);
        redis.set(stateKey(playerId, OPEN_UPDATED_SUFFIX), String.valueOf(now.toEpochMilli()), STATE_TTL_SECONDS);
        redis.set(stateKey(playerId, LAST_MUTATION_SUFFIX), String.valueOf(now.toEpochMilli()), STATE_TTL_SECONDS);
    }

    private boolean loadStateFromRedis(UUID playerId) {
        if (redis == null || !redis.isAvailable()) {
            return false;
        }
        try {
            UUID lastTarget = tryParseUuid(redis.get(stateKey(playerId, LAST_TARGET_SUFFIX)));
            String lastTargetName = redis.get(stateKey(playerId, LAST_TARGET_NAME_SUFFIX));
            String channelId = redis.get(stateKey(playerId, OPEN_CHANNEL_SUFFIX));
            UUID openTarget = parseChannelTarget(channelId);
            Instant openUpdated = parseInstant(redis.get(stateKey(playerId, OPEN_UPDATED_SUFFIX)));
            Instant lastMutation = parseInstant(redis.get(stateKey(playerId, LAST_MUTATION_SUFFIX)));
            DirectMessageState state = new DirectMessageState(lastTarget, lastTargetName, openTarget, channelId, openUpdated, Objects.requireNonNullElse(lastMutation, clock.instant()));
            states.put(playerId, state);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load DM state for " + playerId + ": " + ex.getMessage());
            return false;
        }
    }

    private void clearRemoteState(UUID playerId) {
        if (redis == null || !redis.isAvailable()) {
            return;
        }
        redis.delete(stateKey(playerId, LAST_TARGET_SUFFIX));
        redis.delete(stateKey(playerId, LAST_TARGET_NAME_SUFFIX));
        redis.delete(stateKey(playerId, OPEN_CHANNEL_SUFFIX));
        redis.delete(stateKey(playerId, OPEN_UPDATED_SUFFIX));
        redis.delete(stateKey(playerId, LAST_MUTATION_SUFFIX));
    }

    private void closeOpenChannel(UUID playerId, Player notifyPlayer, Component message, boolean notify) {
        DirectMessageState state = states.get(playerId);
        if (state == null || !state.hasOpenChannel()) {
            return;
        }
        states.computeIfPresent(playerId, (id, existing) -> existing.withoutOpenChannel(clock.instant()));
        if (redis != null && redis.isAvailable()) {
            redis.delete(stateKey(playerId, OPEN_CHANNEL_SUFFIX));
            redis.delete(stateKey(playerId, OPEN_UPDATED_SUFFIX));
        }
        if (notify && notifyPlayer != null && message != null) {
            sendSync(notifyPlayer, message);
        }
    }

    private boolean isChannelExpired(DirectMessageState state) {
        if (!state.hasOpenChannel()) {
            return false;
        }
        Instant updated = state.openChannelUpdated();
        return updated != null && updated.plus(CHANNEL_TTL).isBefore(clock.instant());
    }

    private Component applyEmojis(Player sender, Component message) {
        if (chatEmojiService == null) {
            return message;
        }
        return chatEmojiService.apply(sender, message);
    }

    private void cacheName(UUID playerId, String name) {
        if (playerId == null || name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        idToName.put(playerId, trimmed);
        nameToId.put(trimmed.toLowerCase(Locale.ROOT), playerId);
        if (redis != null && redis.isAvailable()) {
            redis.set(nameKey(trimmed), playerId.toString(), 0);
        }
    }

    private void removeName(UUID playerId) {
        String name = idToName.remove(playerId);
        if (name != null) {
            nameToId.remove(name.toLowerCase(Locale.ROOT));
            if (redis != null && redis.isAvailable()) {
                redis.delete(nameKey(name));
            }
        }
    }

    private String nameKey(String input) {
        return NAME_KEY_PREFIX + input.toLowerCase(Locale.ROOT);
    }

    private String rateKey(UUID playerId) {
        return STATE_KEY_PREFIX + playerId + RATE_LIMIT_SUFFIX;
    }

    private String sessionStateKey(UUID playerId) {
        return SESSION_STATE_PREFIX + playerId + SESSION_STATE_SUFFIX;
    }

    private String stateKey(UUID playerId, String suffix) {
        return STATE_KEY_PREFIX + playerId + suffix;
    }

    private String channelId(UUID targetId) {
        return "player:" + targetId;
    }

    private UUID parseChannelTarget(String channelId) {
        if (channelId == null || !channelId.startsWith("player:")) {
            return null;
        }
        return tryParseUuid(channelId.substring("player:".length()));
    }

    private UUID tryParseUuid(String input) {
        if (input == null) {
            return null;
        }
        try {
            return UUID.fromString(input.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String safeName(TargetHandle handle) {
        return safeName(handle.id(), handle.name());
    }

    private String safeName(UUID playerId, String fallback) {
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return idToName.getOrDefault(playerId, playerId.toString());
    }

    private Rank resolveRank(UUID playerId) {
        if (rankService == null || playerId == null) {
            return null;
        }
        try {
            return rankService.getEffectiveRankSync(playerId);
        } catch (Exception ex) {
            return null;
        }
    }

    private void sendSync(Player player, Component message) {
        if (player == null || message == null) {
            return;
        }
        runSync(() -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }

    private void runSync(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    private record TargetHandle(UUID id, String name) {
        TargetHandle {
            Objects.requireNonNull(id, "id");
        }
    }
}
