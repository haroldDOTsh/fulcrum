package sh.harold.fulcrum.velocity.fundamentals.data;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.player.PlayerDirectory;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.status.PlayerStatus;
import sh.harold.fulcrum.api.status.PresenceStatus;
import sh.harold.fulcrum.api.status.StatusService;
import sh.harold.fulcrum.session.PlayerSessionRecord;
import sh.harold.fulcrum.velocity.session.VelocityPlayerSessionService;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Velocity proxy implementation of {@link PlayerDirectory}.
 */
public final class VelocityPlayerDirectory implements PlayerDirectory {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PLAYERS_COLLECTION = "players";

    private final ProxyServer proxy;
    private final VelocityPlayerSessionService sessionService;
    private final DataAPI dataAPI;
    private final RankService rankService;
    private final StatusService statusService;
    private final Executor executor;
    private final Cache<UUID, PlayerProfile> cache;
    private final Logger logger;

    public VelocityPlayerDirectory(ProxyServer proxy,
                                   VelocityPlayerSessionService sessionService,
                                   DataAPI dataAPI,
                                   RankService rankService,
                                   StatusService statusService,
                                   Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.sessionService = sessionService;
        this.dataAPI = dataAPI;
        this.rankService = rankService;
        this.statusService = statusService;
        this.logger = logger;
        this.executor = dataAPI != null ? dataAPI.executor() : ForkJoinPool.commonPool();
        this.cache = Caffeine.newBuilder()
                .maximumSize(8_000)
                .expireAfterAccess(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public CompletionStage<PlayerProfile> getProfile(UUID playerId, ProfileQuery query) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(PlayerProfile.missing(null));
        }
        ProfileQuery effectiveQuery = query != null ? query : ProfileQuery.DEFAULT;
        if (!effectiveQuery.requireFresh()) {
            PlayerProfile cached = cache.getIfPresent(playerId);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        return CompletableFuture.supplyAsync(() -> loadProfile(playerId, effectiveQuery.includeRankData()), executor)
                .thenApply(profile -> {
                    cache.put(playerId, profile);
                    return profile;
                });
    }

    @Override
    public CompletionStage<Map<UUID, PlayerProfile>> getProfiles(java.util.Collection<UUID> playerIds, ProfileQuery query) {
        if (playerIds == null || playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        ProfileQuery effectiveQuery = query != null ? query : ProfileQuery.DEFAULT;
        Map<UUID, PlayerProfile> resolved = new LinkedHashMap<>();
        if (!effectiveQuery.requireFresh()) {
            for (UUID id : playerIds) {
                if (id == null || resolved.containsKey(id)) {
                    continue;
                }
                PlayerProfile cached = cache.getIfPresent(id);
                if (cached != null) {
                    resolved.put(id, cached);
                }
            }
        }

        java.util.List<UUID> pending = playerIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> effectiveQuery.requireFresh() || !resolved.containsKey(id))
                .distinct()
                .toList();
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(resolved);
        }
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, PlayerProfile> fetched = new LinkedHashMap<>(resolved);
            for (UUID id : pending) {
                PlayerProfile profile = loadProfile(id, effectiveQuery.includeRankData());
                cache.put(id, profile);
                fetched.put(id, profile);
            }
            return fetched;
        }, executor);
    }

    @Override
    public CompletionStage<Optional<PlayerProfile>> findProfileByName(String username, ProfileQuery query) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ProfileQuery effectiveQuery = query != null ? query : ProfileQuery.DEFAULT;
        String normalized = username.trim();
        return CompletableFuture.supplyAsync(() -> {
            Optional<Player> online = proxy.getPlayer(normalized);
            if (online.isPresent()) {
                return Optional.of(loadProfile(online.get().getUniqueId(), effectiveQuery.includeRankData()));
            }
            if (dataAPI == null) {
                return Optional.empty();
            }
            sh.harold.fulcrum.api.data.Collection players = dataAPI.collection(PLAYERS_COLLECTION);
            Document document = players.where("username").equalTo(normalized).limit(1).first();
            if (document == null || !document.exists()) {
                return Optional.empty();
            }
            UUID playerId = UUID.fromString(document.getId());
            PlayerProfile profile = buildFromDocument(playerId, document.toMap(), effectiveQuery.includeRankData());
            cache.put(playerId, profile);
            return Optional.of(profile);
        }, executor);
    }

    @Override
    public void invalidate(UUID playerId) {
        if (playerId == null) {
            return;
        }
        cache.invalidate(playerId);
    }

    @Override
    public Optional<PlayerProfile> peek(UUID playerId) {
        return Optional.ofNullable(cache.getIfPresent(playerId));
    }

    @Override
    public CompletionStage<PlayerStatus> getStatus(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (statusService == null) {
            return CompletableFuture.completedFuture(fallbackStatus(playerId));
        }
        return statusService.getStatus(playerId);
    }

    @Override
    public CompletionStage<Map<UUID, PlayerStatus>> getStatuses(java.util.Collection<UUID> playerIds) {
        if (playerIds == null || playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        if (statusService == null) {
            Map<UUID, PlayerStatus> offline = new LinkedHashMap<>();
            for (UUID id : playerIds) {
                if (id != null) {
                    offline.put(id, fallbackStatus(id));
                }
            }
            return CompletableFuture.completedFuture(offline);
        }
        return statusService.getStatuses(playerIds);
    }

    private PlayerProfile loadProfile(UUID playerId, boolean includeRankData) {
        Optional<Player> online = proxy.getPlayer(playerId);
        if (online.isPresent()) {
            return buildFromOnlinePlayer(online.get(), includeRankData);
        }
        Optional<PlayerSessionRecord> session = sessionService != null
                ? sessionService.getSession(playerId)
                : Optional.empty();
        if (session.isPresent()) {
            return buildFromSession(session.get(), includeRankData);
        }
        if (dataAPI != null) {
            Document document = dataAPI.collection(PLAYERS_COLLECTION).document(playerId.toString());
            if (document.exists()) {
                return buildFromDocument(playerId, document.toMap(), includeRankData);
            }
        }
        return PlayerProfile.missing(playerId);
    }

    private PlayerProfile buildFromOnlinePlayer(Player player, boolean includeRankData) {
        UUID playerId = player.getUniqueId();
        Rank primary = includeRankData && rankService != null ? rankService.getPrimaryRankSync(playerId) : Rank.DEFAULT;
        Rank effective = includeRankData && rankService != null ? rankService.getEffectiveRankSync(playerId) : Rank.DEFAULT;
        Map<String, Object> cosmetics = sessionService != null
                ? sessionService.getSession(playerId).map(PlayerSessionRecord::getCosmetics).orElse(Collections.emptyMap())
                : Collections.emptyMap();
        Component formatted = buildDisplayName(player.getUsername(), effective, cosmetics);
        return new PlayerProfile(playerId, player.getUsername(), primary, effective, Instant.now(), formatted);
    }

    private PlayerProfile buildFromSession(PlayerSessionRecord record, boolean includeRankData) {
        UUID playerId = record.getPlayerId();
        String username = Objects.toString(record.getCore().getOrDefault("username", null), null);
        Rank primary = includeRankData && rankService != null ? rankService.getPrimaryRankSync(playerId) : Rank.DEFAULT;
        Rank effective = includeRankData && rankService != null ? rankService.getEffectiveRankSync(playerId) : Rank.DEFAULT;
        Long lastSeen = asLong(record.getCore().get("lastSeen"));
        Instant seenAt = lastSeen != null ? Instant.ofEpochMilli(lastSeen) : Instant.EPOCH;
        Component formatted = buildDisplayName(username, effective, record.getCosmetics());
        return new PlayerProfile(playerId, username, primary, effective, seenAt, formatted);
    }

    private PlayerProfile buildFromDocument(UUID playerId, Map<String, Object> source, boolean includeRankData) {
        if (source == null) {
            return PlayerProfile.missing(playerId);
        }
        String username = Objects.toString(source.get("username"), null);
        Rank primary = includeRankData && rankService != null ? rankService.getPrimaryRankSync(playerId) : Rank.DEFAULT;
        Rank effective = includeRankData && rankService != null ? rankService.getEffectiveRankSync(playerId) : Rank.DEFAULT;
        Long lastSeen = asLong(source.get("lastSeen"));
        Instant seenAt = lastSeen != null ? Instant.ofEpochMilli(lastSeen) : Instant.EPOCH;
        Map<String, Object> cosmetics = asMap(source.get("cosmetics"));
        Component formatted = buildDisplayName(username, effective, cosmetics);
        return new PlayerProfile(playerId, username, primary, effective, seenAt, formatted);
    }

    private Component buildDisplayName(String username, Rank rank, Map<String, Object> cosmetics) {
        String safeName = username != null ? username : "Unknown";
        ChatCosmetics overrides = resolveChatCosmetics(cosmetics);
        TextColor nameColor = overrides.nameColorOverride() != null
                ? overrides.nameColorOverride()
                : (rank != null ? rank.getTextColor() : NamedTextColor.GRAY);
        Component nameComponent = Component.text(safeName).color(nameColor);

        String prefixSource = overrides.rankVisualOverride() != null
                ? overrides.rankVisualOverride().fullPrefix()
                : firstNonBlank(rank.getFullPrefix(), rank.getShortPrefix());
        if (prefixSource == null || prefixSource.isBlank()) {
            return nameComponent;
        }
        Component prefixComponent = LEGACY.deserialize(prefixSource);
        if (prefixComponent.equals(Component.empty())) {
            return nameComponent;
        }
        return Component.text().append(prefixComponent).append(Component.space()).append(nameComponent).build();
    }

    private ChatCosmetics resolveChatCosmetics(Map<String, Object> cosmetics) {
        if (cosmetics == null || cosmetics.isEmpty()) {
            return ChatCosmetics.EMPTY;
        }
        Map<String, Object> chatSection = asMap(cosmetics.get("chat"));
        if (chatSection.isEmpty()) {
            return ChatCosmetics.EMPTY;
        }
        RankVisualOverride override = buildRankVisualOverride(chatSection.get("rankVisualOverride"));
        TextColor nameColor = parseTextColor(chatSection.get("nameColorOverride"));
        return override == null && nameColor == null
                ? ChatCosmetics.EMPTY
                : new ChatCosmetics(override, nameColor);
    }

    private RankVisualOverride buildRankVisualOverride(Object raw) {
        Map<String, Object> values = asMap(raw);
        if (values.isEmpty()) {
            return null;
        }
        String displayName = sanitize(values.get("displayName"));
        String colorCode = sanitize(values.get("colorCode"));
        String fullPrefix = sanitize(values.get("fullPrefix"));
        String shortPrefix = sanitize(values.get("shortPrefix"));
        String nameColor = sanitize(values.get("nameColor"));

        boolean hasOverride = !displayName.isEmpty()
                || !colorCode.isEmpty()
                || !fullPrefix.isEmpty()
                || !shortPrefix.isEmpty()
                || !nameColor.isEmpty();
        if (!hasOverride) {
            return null;
        }
        return new RankVisualOverride(displayName, colorCode, fullPrefix, shortPrefix, nameColor);
    }

    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private String sanitize(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().trim();
    }

    private TextColor parseTextColor(Object raw) {
        if (!(raw instanceof String token)) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("&")) {
            Component colored = LEGACY.deserialize(trimmed + "x");
            if (colored.color() != null) {
                return colored.color();
            }
            trimmed = trimmed.substring(1);
        }
        if (trimmed.startsWith("&#") && trimmed.length() == 8) {
            trimmed = "#" + trimmed.substring(2);
        }
        if (trimmed.length() == 6 && trimmed.chars().allMatch(Character::isLetterOrDigit)) {
            trimmed = "#" + trimmed;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
        if (named != null) {
            return named;
        }
        try {
            return TextColor.fromHexString(trimmed);
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.debug("Failed to parse chat color override {}", trimmed, ex);
            }
            return null;
        }
    }

    private String firstNonBlank(String... values) {
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

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private PlayerStatus fallbackStatus(UUID playerId) {
        return new PlayerStatus(playerId, PresenceStatus.OFFLINE, null, 0L);
    }

    private record RankVisualOverride(String displayName,
                                      String colorCode,
                                      String fullPrefix,
                                      String shortPrefix,
                                      String nameColor) {
    }

    private record ChatCosmetics(RankVisualOverride rankVisualOverride, TextColor nameColorOverride) {
        private static final ChatCosmetics EMPTY = new ChatCosmetics(null, null);
    }
}
