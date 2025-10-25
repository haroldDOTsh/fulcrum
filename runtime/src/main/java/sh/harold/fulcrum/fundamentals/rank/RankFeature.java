package sh.harold.fulcrum.fundamentals.rank;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.data.Collection;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.messagebus.*;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationRequestMessage;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankMutationResponseMessage;
import sh.harold.fulcrum.api.messagebus.messages.rank.RankSyncMessage;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankChangeContext;
import sh.harold.fulcrum.api.rank.RankMutationType;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.fundamentals.session.PlayerSessionService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.session.PlayerSessionRecord;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Runtime-side rank feature.
 * <p>
 * This implementation is now read-only with respect to persistence.
 * Any mutation requests are forwarded to the registry service over the message bus.
 */
public final class RankFeature implements PluginFeature, RankService, Listener {

    private static final String PLAYERS_COLLECTION = "players";
    private static final String REGISTRY_SERVER_ID = "registry-service";
    private static final Duration MUTATION_TIMEOUT = Duration.ofSeconds(10);

    private final Map<UUID, Set<Rank>> rankCache = new ConcurrentHashMap<>();
    private final Map<UUID, Rank> primaryRankCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<RankMutationResponseMessage>> pendingMutations = new ConcurrentHashMap<>();

    private JavaPlugin plugin;
    private Logger logger;
    private DataAPI dataAPI;
    private Collection playersCollection;
    private PlayerSessionService sessionService;
    private MessageBus messageBus;
    private MessageHandler mutationResponseHandler;
    private MessageHandler rankUpdateHandler;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();

        this.dataAPI = container.get(DataAPI.class);
        if (dataAPI == null) {
            logger.warning("DataAPI not available; ranks will not persist across restarts!");
            return;
        }

        this.playersCollection = dataAPI.collection(PLAYERS_COLLECTION);
        this.sessionService = container.getOptional(PlayerSessionService.class).orElse(null);
        this.messageBus = container.getOptional(MessageBus.class).orElse(null);

        container.getOptional(NetworkConfigService.class)
                .ifPresent(this::installVisualResolver);

        container.register(RankService.class, this);
        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.registerService(RankService.class, this));

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        subscribeMessageBus();

        logger.info("Rank feature initialised (runtime operates in read-only mode; mutations handled by registry).");
    }

    @Override
    public void shutdown() {
        unsubscribeMessageBus();
        rankCache.clear();
        primaryRankCache.clear();
        pendingMutations.values().forEach(future ->
                future.completeExceptionally(new IllegalStateException("Rank feature shutting down")));
        pendingMutations.clear();

        Optional.ofNullable(ServiceLocatorImpl.getInstance())
                .ifPresent(locator -> locator.unregisterService(RankService.class));

        logger.info("Rank feature shut down");
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        loadPlayerRanks(playerId).whenComplete((ignored, throwable) -> plugin.getServer().getScheduler().runTaskLater(
                plugin,
                player::updateCommands,
                10L
        ));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        rankCache.remove(playerId);
        primaryRankCache.remove(playerId);
    }

    @Override
    public CompletableFuture<Rank> getPrimaryRank(UUID playerId) {
        Rank cached = primaryRankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return loadPlayerRanks(playerId).thenApply(this::getEffectiveRankFromSet);
    }

    @Override
    public Rank getPrimaryRankSync(UUID playerId) {
        return primaryRankCache.getOrDefault(playerId, Rank.DEFAULT);
    }

    @Override
    public CompletableFuture<Set<Rank>> getAllRanks(UUID playerId) {
        Set<Rank> cached = rankCache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(new HashSet<>(cached));
        }
        return loadPlayerRanks(playerId).thenApply(HashSet::new);
    }

    @Override
    public CompletableFuture<Void> setPrimaryRank(UUID playerId, Rank rank, RankChangeContext context) {
        return sendMutation(playerId, RankMutationType.SET_PRIMARY, rank, context);
    }

    @Override
    public CompletableFuture<Void> addRank(UUID playerId, Rank rank, RankChangeContext context) {
        return sendMutation(playerId, RankMutationType.ADD, rank, context);
    }

    @Override
    public CompletableFuture<Void> removeRank(UUID playerId, Rank rank, RankChangeContext context) {
        return sendMutation(playerId, RankMutationType.REMOVE, rank, context);
    }

    @Override
    public CompletableFuture<Rank> getEffectiveRank(UUID playerId) {
        return getAllRanks(playerId).thenApply(this::getEffectiveRankFromSet);
    }

    @Override
    public Rank getEffectiveRankSync(UUID playerId) {
        return getEffectiveRankFromSet(rankCache.getOrDefault(playerId, Set.of()));
    }

    @Override
    public CompletableFuture<Boolean> hasRank(UUID playerId, Rank rank) {
        return getAllRanks(playerId).thenApply(ranks -> ranks.contains(rank));
    }

    @Override
    public CompletableFuture<Boolean> isStaff(UUID playerId) {
        return getAllRanks(playerId).thenApply(ranks -> ranks.stream().anyMatch(Rank::isStaff));
    }

    @Override
    public CompletableFuture<Void> resetRanks(UUID playerId, RankChangeContext context) {
        return sendMutation(playerId, RankMutationType.RESET, null, context);
    }

    private void subscribeMessageBus() {
        if (messageBus == null) {
            logger.warning("MessageBus not available; rank mutations cannot be forwarded to registry.");
            return;
        }

        mutationResponseHandler = this::handleMutationResponse;
        rankUpdateHandler = this::handleRankSync;

        messageBus.subscribe(ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE, mutationResponseHandler);
        messageBus.subscribe(ChannelConstants.REGISTRY_RANK_UPDATE, rankUpdateHandler);
    }

    private void installVisualResolver(NetworkConfigService configService) {
        Rank.setVisualResolver(new NetworkRankVisualResolver(configService));
    }

    private void unsubscribeMessageBus() {
        if (messageBus != null) {
            if (mutationResponseHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_RANK_MUTATION_RESPONSE, mutationResponseHandler);
            }
            if (rankUpdateHandler != null) {
                messageBus.unsubscribe(ChannelConstants.REGISTRY_RANK_UPDATE, rankUpdateHandler);
            }
        }
    }

    private CompletableFuture<Void> sendMutation(UUID playerId,
                                                 RankMutationType mutationType,
                                                 Rank rank,
                                                 RankChangeContext context) {
        if (messageBus == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("MessageBus is not available"));
        }
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(mutationType, "mutationType");

        UUID requestId = UUID.randomUUID();
        RankMutationRequestMessage request = new RankMutationRequestMessage(
                requestId,
                playerId,
                mutationType,
                rank != null ? rank.name() : null,
                context != null ? context : RankChangeContext.system(),
                resolvePlayerName(playerId)
        );

        CompletableFuture<RankMutationResponseMessage> responseFuture = new CompletableFuture<>();
        pendingMutations.put(requestId, responseFuture);
        try {
            messageBus.send(REGISTRY_SERVER_ID, ChannelConstants.REGISTRY_RANK_MUTATION_REQUEST, request);
        } catch (Exception ex) {
            pendingMutations.remove(requestId);
            return CompletableFuture.failedFuture(ex);
        }

        CompletableFuture<Void> result = responseFuture
                .orTimeout(MUTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .thenCompose(response -> {
                    if (!response.isSuccess()) {
                        String error = response.getError() != null ? response.getError() : "Unknown rank mutation failure";
                        return CompletableFuture.failedFuture(new IllegalStateException(error));
                    }
                    applyRankState(playerId, response.getPrimaryRankId(), response.getRankIds());
                    return CompletableFuture.completedFuture(null);
                });

        return result.whenComplete((ignored, throwable) -> pendingMutations.remove(requestId));
    }

    private void handleMutationResponse(MessageEnvelope envelope) {
        try {
            RankMutationResponseMessage response = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), RankMutationResponseMessage.class);
            if (response == null || response.getRequestId() == null) {
                return;
            }
            CompletableFuture<RankMutationResponseMessage> pending = pendingMutations.get(response.getRequestId());
            if (pending != null) {
                pending.complete(response);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to handle rank mutation response", ex);
        }
    }

    private void handleRankSync(MessageEnvelope envelope) {
        try {
            RankSyncMessage message = MessageTypeRegistry.getInstance()
                    .deserializeToClass(envelope.payload(), RankSyncMessage.class);
            if (message == null || message.getPlayerId() == null) {
                return;
            }
            applyRankState(message.getPlayerId(), message.getPrimaryRankId(), message.getRankIds());
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to process rank sync message", ex);
        }
    }

    private void applyRankState(UUID playerId, String primaryRankId, Set<String> rankIds) {
        Rank primary = parseRank(primaryRankId);
        if (primary == null) {
            primary = Rank.DEFAULT;
        }

        Set<Rank> ranks = new HashSet<>();
        if (rankIds != null && !rankIds.isEmpty()) {
            for (String id : rankIds) {
                Rank parsed = parseRank(id);
                if (parsed != null) {
                    ranks.add(parsed);
                }
            }
        }
        if (ranks.isEmpty()) {
            ranks.add(primary);
        } else {
            ranks.add(primary);
        }

        rankCache.put(playerId, ranks);
        primaryRankCache.put(playerId, primary);
        updateSessionRank(playerId, primary, ranks);
        updateOnlinePlayerCommands(playerId);
    }

    private void updateOnlinePlayerCommands(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null && player.isOnline()) {
            plugin.getServer().getScheduler().runTask(plugin, player::updateCommands);
        }
    }

    private CompletableFuture<Set<Rank>> loadPlayerRanks(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            Set<Rank> ranks = readRanksFromPlayerDocument(playerId);
            rankCache.put(playerId, ranks);
            primaryRankCache.put(playerId, getEffectiveRankFromSet(ranks));
            return ranks;
        });
    }

    private Set<Rank> readRanksFromPlayerDocument(UUID playerId) {
        try {
            Document doc = playersCollection.document(playerId.toString());
            Set<Rank> ranks = new HashSet<>();

            Object storedRanks = doc.get("rankInfo.all");
            if (storedRanks instanceof List<?> list) {
                for (Object value : list) {
                    Rank parsed = parseRank(value);
                    if (parsed != null) {
                        ranks.add(parsed);
                    }
                }
            }

            String primaryName = doc.get("rankInfo.primary", null);
            Rank primary = parseRank(primaryName);
            if (primary != null) {
                ranks.add(primary);
            }

            return ranks;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load rank data for " + playerId, e);
            return new HashSet<>();
        }
    }

    private Rank parseRank(Object value) {
        if (value == null) {
            return null;
        }
        String name = value.toString().trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            return Rank.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.fine("Unknown rank encountered: " + name);
            return null;
        }
    }

    private Rank getEffectiveRankFromSet(Set<Rank> ranks) {
        if (ranks == null || ranks.isEmpty()) {
            return Rank.DEFAULT;
        }
        return ranks.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(Rank::getPriority))
                .orElse(Rank.DEFAULT);
    }

    private void updateSessionRank(UUID playerId, Rank primary, Set<Rank> ranks) {
        if (sessionService == null) {
            return;
        }

        sessionService.withActiveSession(playerId, record -> {
            Map<String, Object> rankInfo = record.getRank();
            rankInfo.clear();

            List<String> orderedRanks = ranks.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(Rank::getPriority).reversed())
                    .map(Enum::name)
                    .collect(Collectors.toList());

            if (!orderedRanks.isEmpty()) {
                rankInfo.put("all", orderedRanks);
            }
            if (primary != null) {
                rankInfo.put("primary", primary.name());
            }

            if (primary == null || primary == Rank.DEFAULT) {
                record.getCore().remove("rank");
            } else {
                record.getCore().put("rank", primary.name());
            }
        });
    }

    private String resolvePlayerName(UUID playerId) {
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        if (sessionService != null) {
            Optional<PlayerSessionRecord> session = sessionService.getActiveSession(playerId);
            if (session.isPresent()) {
                Object username = session.get().getCore().get("username");
                if (username != null) {
                    return username.toString();
                }
            }
        }
        return null;
    }
}
