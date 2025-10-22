package sh.harold.fulcrum.fundamentals.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.chat.ChatFormatService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelRef;
import sh.harold.fulcrum.api.chat.channel.ChatChannelService;
import sh.harold.fulcrum.api.chat.channel.ChatChannelType;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.messages.chat.ChatChannelMessage;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyMessageAction;
import sh.harold.fulcrum.api.messagebus.messages.party.PartyUpdateMessage;
import sh.harold.fulcrum.api.party.PartyRedisKeys;
import sh.harold.fulcrum.api.party.PartySnapshot;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.runtime.redis.LettuceRedisOperations;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class ChatChannelServiceImpl implements ChatChannelService {
    private static final long SLOW_MODE_MILLIS = 3000L;
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final JavaPlugin plugin;
    private final Logger logger;
    private final MessageBus messageBus;
    private final ChatFormatService chatFormatService;
    private final RankService rankService;
    private final LettuceRedisOperations redisOperations;
    private final ObjectMapper mapper;

    private final Map<UUID, ChatChannelRef> activeChannels = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerParty = new ConcurrentHashMap<>();
    private final Map<UUID, PartySnapshot> partySnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAllMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPartyMessage = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastDeliveredMessage = new ConcurrentHashMap<>();

    ChatChannelServiceImpl(JavaPlugin plugin,
                           MessageBus messageBus,
                           ChatFormatService chatFormatService,
                           RankService rankService,
                           LettuceRedisOperations redisOperations) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messageBus = messageBus;
        this.chatFormatService = chatFormatService;
        this.rankService = rankService;
        this.redisOperations = redisOperations;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public ChatChannelRef getActiveChannel(UUID playerId) {
        return activeChannels.getOrDefault(playerId, ChatChannelRef.all());
    }

    @Override
    public void switchChannel(Player player, ChatChannelType type) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");

        switch (type) {
            case ALL -> setChannel(player, ChatChannelRef.all());
            case STAFF -> handleStaffSwitch(player);
            case PARTY -> handlePartySwitch(player);
        }
    }

    @Override
    public void quickSend(Player player, ChatChannelType type, String rawMessage) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(type, "type");
        if (rawMessage == null || rawMessage.isBlank()) {
            player.sendMessage(Component.text("You must include a message.", NamedTextColor.RED));
            return;
        }

        Component messageComponent = Component.text(rawMessage);
        String plain = rawMessage;

        switch (type) {
            case PARTY -> findPartyId(player.getUniqueId()).ifPresentOrElse(
                    partyId -> runSync(() -> sendChannelMessage(player, ChatChannelRef.party(partyId), messageComponent, plain)),
                    () -> player.sendMessage(Component.text("You are not currently in a party.", NamedTextColor.RED))
            );
            case STAFF -> {
                if (!isStaff(player.getUniqueId())) {
                    player.sendMessage(Component.text("Staff chat is restricted to staff members.", NamedTextColor.RED));
                    return;
                }
                runSync(() -> sendChannelMessage(player, ChatChannelRef.staff(), messageComponent, plain));
            }
            case ALL -> player.sendMessage(Component.text("Use the regular chat to speak in ALL.", NamedTextColor.RED));
        }
    }

    @Override
    public Optional<UUID> findPartyId(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        UUID cached = playerParty.get(playerId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return fetchPartyMembership(playerId);
    }

    @Override
    public void handleAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ChatChannelRef current = getActiveChannel(player.getUniqueId());

        if (current.type() == ChatChannelType.ALL) {
            if (!checkSlowMode(player.getUniqueId(), ChatChannelType.ALL, lastAllMessage)) {
                event.setCancelled(true);
                notifySlowMode(player);
            }
            return;
        }

        event.setCancelled(true);
        Component message = event.message();
        String plain = PLAIN.serialize(message);
        runSync(() -> sendChannelMessage(player, current, message, plain));
    }

    @Override
    public void handleIncomingMessage(ChatChannelMessage message) {
        Component component = decodeComponent(message);
        UUID messageId = message.getMessageId();
        if (messageId == null) {
            return;
        }

        String channelId = message.getChannelId();
        if (ChatChannelRef.STAFF_CHANNEL_ID.equalsIgnoreCase(channelId)) {
            deliverToStaff(component, messageId);
            return;
        }

        ChatChannelRef.parsePartyId(channelId).ifPresent(partyId -> deliverToParty(partyId, component, messageId));
    }

    @Override
    public void handlePartyUpdate(PartyUpdateMessage message) {
        UUID partyId = message.getPartyId();
        if (partyId == null) {
            return;
        }

        if (message.getAction() == PartyMessageAction.DISBANDED) {
            handlePartyDisband(partyId);
            return;
        }

        PartySnapshot snapshot = message.getSnapshot();
        if (snapshot != null) {
            applySnapshot(snapshot);
            return;
        }

        fetchPartySnapshot(partyId).ifPresent(this::applySnapshot);
    }

    @Override
    public void handlePlayerJoin(Player player) {
        UUID playerId = player.getUniqueId();
        activeChannels.put(playerId, ChatChannelRef.all());
        findPartyId(playerId);
    }

    @Override
    public void handlePlayerQuit(UUID playerId) {
        activeChannels.remove(playerId);
        lastAllMessage.remove(playerId);
        lastPartyMessage.remove(playerId);
        lastDeliveredMessage.remove(playerId);
    }

    private void handleStaffSwitch(Player player) {
        if (!isStaff(player.getUniqueId())) {
            player.sendMessage(Component.text("Staff chat is restricted to staff members.", NamedTextColor.RED));
            return;
        }
        setChannel(player, ChatChannelRef.staff());
    }

    private void handlePartySwitch(Player player) {
        UUID playerId = player.getUniqueId();
        findPartyId(playerId).ifPresentOrElse(
                partyId -> setChannel(player, ChatChannelRef.party(partyId)),
                () -> player.sendMessage(Component.text("You are not currently in a party.", NamedTextColor.RED))
        );
    }

    private void setChannel(Player player, ChatChannelRef ref) {
        activeChannels.put(player.getUniqueId(), ref);
        Component message = Component.text("You are now in the ", NamedTextColor.GREEN)
                .append(Component.text(ref.type().getDisplayName(), NamedTextColor.GOLD))
                .append(Component.text(" channel.", NamedTextColor.GREEN));
        player.sendMessage(message);
    }

    private void sendChannelMessage(Player sender,
                                    ChatChannelRef channel,
                                    Component rawMessage,
                                    String plainText) {
        if (channel.type() == ChatChannelType.PARTY
                && !checkSlowMode(sender.getUniqueId(), ChatChannelType.PARTY, lastPartyMessage)) {
            notifySlowMode(sender);
            return;
        }

        if (channel.type() == ChatChannelType.PARTY) {
            UUID partyId = channel.partyId();
            if (!partyId.equals(playerParty.get(sender.getUniqueId()))) {
                // stale cache; attempt refresh before failing
                if (fetchPartyMembership(sender.getUniqueId()).map(id -> id.equals(partyId)).orElse(false)) {
                    playerParty.put(sender.getUniqueId(), partyId);
                } else {
                    sender.sendMessage(Component.text("Party chat unavailable; we could not confirm your membership.", NamedTextColor.RED));
                    switchChannel(sender, ChatChannelType.ALL);
                    return;
                }
            }
        }

        UUID messageId = UUID.randomUUID();
        Component formatted = buildFormattedMessage(sender, channel, rawMessage);
        deliverLocally(channel, formatted, messageId);
        publish(channel, sender.getUniqueId(), formatted, plainText, messageId);
    }

    private Component buildFormattedMessage(Player sender, ChatChannelRef channel, Component rawMessage) {
        Component base = chatFormatService != null
                ? chatFormatService.formatMessage(sender, rawMessage)
                : Component.text(sender.getName(), NamedTextColor.WHITE)
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(rawMessage);

        Component whiteBase = base.colorIfAbsent(NamedTextColor.WHITE);

        return switch (channel.type()) {
            case ALL -> base;
            case STAFF -> Component.text("Staff > ", NamedTextColor.AQUA).append(whiteBase);
            case PARTY -> Component.text("Party > ", NamedTextColor.BLUE).append(whiteBase);
        };
    }

    private void deliverLocally(ChatChannelRef channel, Component message, UUID messageId) {
        switch (channel.type()) {
            case STAFF -> deliverToStaff(message, messageId);
            case PARTY -> deliverToParty(channel.partyId(), message, messageId);
            case ALL -> {
                // no-op; handled by Paper pipeline
            }
        }
    }

    private void deliverToStaff(Component message, UUID messageId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isStaff(player.getUniqueId())) {
                deliverIfNew(player, message, messageId);
            }
        }
    }

    private void deliverToParty(UUID partyId, Component message, UUID messageId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID memberParty = playerParty.get(player.getUniqueId());
            if (partyId.equals(memberParty)) {
                deliverIfNew(player, message, messageId);
            }
        }
    }

    private void deliverIfNew(Player player, Component message, UUID messageId) {
        UUID last = lastDeliveredMessage.get(player.getUniqueId());
        if (messageId.equals(last)) {
            return;
        }
        player.sendMessage(message);
        lastDeliveredMessage.put(player.getUniqueId(), messageId);
    }

    private void publish(ChatChannelRef channel,
                         UUID senderId,
                         Component message,
                         String plainText,
                         UUID messageId) {
        if (messageBus == null) {
            return;
        }
        ChatChannelMessage payload = new ChatChannelMessage();
        payload.setMessageId(messageId);
        payload.setChannelId(channel.channelId());
        payload.setSenderId(senderId);
        payload.setComponentJson(GSON.serialize(message));
        payload.setPlainText(plainText != null ? plainText : PLAIN.serialize(message));
        payload.setTimestamp(Instant.now().toEpochMilli());
        messageBus.broadcast(ChannelConstants.CHAT_CHANNEL_MESSAGE, payload);
    }

    private Component decodeComponent(ChatChannelMessage message) {
        String json = message.getComponentJson();
        if (json != null && !json.isBlank()) {
            try {
                return GSON.deserialize(json);
            } catch (Exception ex) {
                logger.warning("Failed to decode chat component for channel " + message.getChannelId() + ": " + ex.getMessage());
            }
        }
        String plain = message.getPlainText();
        if (plain == null) {
            plain = "";
        }
        return Component.text(plain, NamedTextColor.GRAY);
    }

    private boolean checkSlowMode(UUID playerId, ChatChannelType type, Map<UUID, Long> buckets) {
        if (!isSlowModeRestricted(playerId, type)) {
            return true;
        }
        long now = System.currentTimeMillis();
        long last = buckets.getOrDefault(playerId, 0L);
        if (now - last < SLOW_MODE_MILLIS) {
            return false;
        }
        buckets.put(playerId, now);
        return true;
    }

    private boolean isSlowModeRestricted(UUID playerId, ChatChannelType type) {
        if (type == ChatChannelType.STAFF) {
            return false;
        }
        Rank rank = rankService != null ? rankService.getEffectiveRankSync(playerId) : Rank.DEFAULT;
        return rank == null || rank == Rank.DEFAULT;
    }

    private void notifySlowMode(Player player) {
        player.sendMessage(Component.text("You are chatting too quickly. Please wait a moment.", NamedTextColor.RED));
    }

    private boolean isStaff(UUID playerId) {
        Rank rank = rankService != null ? rankService.getEffectiveRankSync(playerId) : Rank.DEFAULT;
        return rank != null && rank.isStaff();
    }

    private Optional<UUID> fetchPartyMembership(UUID playerId) {
        if (redisOperations == null) {
            return Optional.empty();
        }
        String raw = redisOperations.get(PartyRedisKeys.partyMembersLookupKey(playerId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID partyId = UUID.fromString(raw);
            playerParty.put(playerId, partyId);
            fetchPartySnapshot(partyId).ifPresent(this::applySnapshot);
            return Optional.of(partyId);
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid party id '" + raw + "' stored for player " + playerId);
            return Optional.empty();
        }
    }

    private Optional<PartySnapshot> fetchPartySnapshot(UUID partyId) {
        if (redisOperations == null) {
            return Optional.empty();
        }
        String raw = redisOperations.get(PartyRedisKeys.partyDataKey(partyId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            PartySnapshot snapshot = mapper.readValue(raw, PartySnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (Exception ex) {
            logger.warning("Failed to parse party snapshot for " + partyId + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private void applySnapshot(PartySnapshot snapshot) {
        if (snapshot.getPartyId() == null) {
            return;
        }
        UUID partyId = snapshot.getPartyId();

        PartySnapshot previous = partySnapshots.put(partyId, snapshot);
        Set<UUID> previousMembers = previous != null
                ? previous.getMembers().keySet()
                : Collections.emptySet();
        Set<UUID> currentMembers = snapshot.getMembers().keySet();

        List<UUID> removed = new ArrayList<>();
        for (UUID member : previousMembers) {
            if (!currentMembers.contains(member)) {
                if (playerParty.remove(member, partyId)) {
                    removed.add(member);
                }
            }
        }
        for (UUID member : currentMembers) {
            playerParty.put(member, partyId);
        }

        if (!removed.isEmpty()) {
            runSync(() -> removed.forEach(this::fallbackToAllIfNeeded));
        }
    }

    private void handlePartyDisband(UUID partyId) {
        PartySnapshot removed = partySnapshots.remove(partyId);
        List<UUID> affected = new ArrayList<>();
        if (removed != null) {
            removed.getMembers().keySet().forEach(member -> {
                if (playerParty.remove(member, partyId)) {
                    affected.add(member);
                }
            });
        } else {
            playerParty.forEach((playerId, mapped) -> {
                if (partyId.equals(mapped)) {
                    playerParty.remove(playerId, mapped);
                    affected.add(playerId);
                }
            });
        }

        if (!affected.isEmpty()) {
            runSync(() -> affected.forEach(this::fallbackToAllIfNeeded));
        }
    }

    private void fallbackToAllIfNeeded(UUID playerId) {
        ChatChannelRef current = getActiveChannel(playerId);
        if (!current.isParty()) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            activeChannels.put(playerId, ChatChannelRef.all());
            return;
        }
        activeChannels.put(playerId, ChatChannelRef.all());
        player.sendMessage(Component.text("Party chat closed; you have been moved to ALL.", NamedTextColor.YELLOW));
    }

    private void runSync(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }
}
