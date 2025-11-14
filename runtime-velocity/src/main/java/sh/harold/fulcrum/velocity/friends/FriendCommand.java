package sh.harold.fulcrum.velocity.friends;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.common.cache.PlayerCache;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.identity.VelocityIdentityFeature;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class FriendCommand implements SimpleCommand {

    private static final Component FRAME_LINE = Component.text("-----------------------------------------------------", NamedTextColor.BLUE)
            .decorate(TextDecoration.STRIKETHROUGH);
    private static final int PAGE_SIZE = 8;

    private final FriendService friendService;
    private final ProxyServer proxy;
    private final FulcrumVelocityPlugin plugin;
    private final PlayerCache playerCache;
    private final VelocityIdentityFeature identityFeature;
    private final RankService rankService;
    private final Logger logger;
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> reverseNameCache = new ConcurrentHashMap<>();

    public FriendCommand(FriendService friendService,
                         ProxyServer proxy,
                         FulcrumVelocityPlugin plugin,
                         PlayerCache playerCache,
                         VelocityIdentityFeature identityFeature,
                         RankService rankService,
                         Logger logger) {
        this.friendService = Objects.requireNonNull(friendService, "friendService");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerCache = playerCache;
        this.identityFeature = identityFeature;
        this.rankService = rankService;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static UUID tryParseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int parsePage(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private static String shortUuid(UUID uuid) {
        return uuid.toString().split("-")[0];
    }

    private static String messageFromThrowable(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return throwable.getMessage();
        }
        if (throwable.getCause() != null) {
            return messageFromThrowable(throwable.getCause());
        }
        return "Request failed.";
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can use friend commands.", NamedTextColor.RED));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(player);
            case "list" -> handleList(player, args);
            case "requests" -> handleRequests(player, args);
            case "add" -> requireArgument(player, args, 2, () -> handleAdd(player, args[1]));
            case "accept" -> requireArgument(player, args, 2, () -> handleAccept(player, args[1]));
            case "deny" -> requireArgument(player, args, 2, () -> handleDeny(player, args[1]));
            case "remove" -> requireArgument(player, args, 2, () -> handleRemove(player, args[1]));
            case "removeall" -> handleRemoveAll(player);
            case "notifications" -> handleNotifications(player);
            default -> sendHelp(player);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player player)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return subcommands();
        }
        if (args.length == 1) {
            return subcommands().stream()
                    .filter(entry -> startsWithIgnoreCase(entry, args[0]))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && Set.of("accept", "deny", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            return snapshotNames(player).stream()
                    .filter(name -> startsWithIgnoreCase(name, args[1]))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private List<String> subcommands() {
        return List.of("help", "list", "requests", "add", "accept", "deny", "remove", "removeall", "notifications");
    }

    private void sendHelp(Player player) {
        List<Component> lines = List.of(
                FriendTextFormatter.aqua("/friend accept <player> ").append(FriendTextFormatter.gray("Accept a friend request")),
                FriendTextFormatter.aqua("/friend add <player> ").append(FriendTextFormatter.gray("Add a player as a friend")),
                FriendTextFormatter.aqua("/friend deny <player> ").append(FriendTextFormatter.gray("Decline a friend request")),
                FriendTextFormatter.aqua("/friend help ").append(FriendTextFormatter.gray("Show all available friend commands")),
                FriendTextFormatter.aqua("/friend list <page|best> ").append(FriendTextFormatter.gray("List your friends")),
                FriendTextFormatter.aqua("/friend notifications ").append(FriendTextFormatter.gray("Toggle friend join/leave notifications")),
                FriendTextFormatter.aqua("/friend remove <player> ").append(FriendTextFormatter.gray("Remove a player from your friends")),
                FriendTextFormatter.aqua("/friend removeall ").append(FriendTextFormatter.gray("Remove all friends")),
                FriendTextFormatter.aqua("/friend requests <page> ").append(FriendTextFormatter.gray("View friend requests"))
        );
        sendFramed(player, lines);
    }

    private void handleList(Player player, String[] args) {
        if (args.length > 1 && "best".equalsIgnoreCase(args[1])) {
            sendFramed(player, FriendTextFormatter.gray("Best friends will be available soon."));
            return;
        }
        final int requestedPage = args.length > 1 ? parsePage(args[1]) : 1;
        withSnapshot(player, false, snapshot -> {
            List<UUID> friends = new ArrayList<>(snapshot.friends());
            if (friends.isEmpty()) {
                sendFramed(player, FriendTextFormatter.gray("You have no friends yet. Use /friend add <player> to get started."));
                return;
            }
            friends.sort(Comparator.comparing(uuid -> cachedName(uuid).toLowerCase(Locale.ROOT)));
            int totalPages = Math.max(1, (int) Math.ceil(friends.size() / (double) PAGE_SIZE));
            int page = Math.min(Math.max(requestedPage, 1), totalPages);
            int start = (page - 1) * PAGE_SIZE;
            List<UUID> slice = friends.subList(start, Math.min(start + PAGE_SIZE, friends.size()));
            List<Component> lines = new ArrayList<>();
            lines.add(FriendTextFormatter.aqua("Friends (" + friends.size() + ") - Page " + page + "/" + totalPages));
            for (UUID id : slice) {
                String display = cachedName(id);
                boolean online = proxy.getPlayer(id).isPresent();
                Component line = Component.text()
                        .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                        .append(FriendTextFormatter.formatName(id, display, rankService, logger))
                        .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                        .append(online ? FriendTextFormatter.green("Online") : FriendTextFormatter.gray("Offline"))
                        .build();
                lines.add(line);
            }
            sendFramed(player, lines);
        });
    }

    private void handleRequests(Player player, String[] args) {
        final int requestedPage = args.length > 1 ? parsePage(args[1]) : 1;
        withSnapshot(player, false, snapshot -> {
            List<UUID> requests = new ArrayList<>(snapshot.incomingRequests());
            if (requests.isEmpty()) {
                sendFramed(player, FriendTextFormatter.gray("You do not have any pending requests."));
                return;
            }
            requests.sort(Comparator.comparing(uuid -> cachedName(uuid).toLowerCase(Locale.ROOT)));
            int totalPages = Math.max(1, (int) Math.ceil(requests.size() / (double) PAGE_SIZE));
            int page = Math.min(Math.max(requestedPage, 1), totalPages);
            int start = (page - 1) * PAGE_SIZE;
            List<UUID> slice = requests.subList(start, Math.min(start + PAGE_SIZE, requests.size()));
            List<Component> lines = new ArrayList<>();
            lines.add(FriendTextFormatter.aqua("Incoming requests (" + requests.size() + ") - Page " + page + "/" + totalPages));
            for (UUID id : slice) {
                String display = cachedName(id);
                Component line = Component.text()
                        .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                        .append(FriendTextFormatter.formatName(id, display, rankService, logger))
                        .append(Component.text(" - "))
                        .append(Component.text("Use /friend accept " + display, NamedTextColor.YELLOW))
                        .build();
                lines.add(line);
            }
            sendFramed(player, lines);
        });
    }

    private void handleAdd(Player player, String targetArg) {
        resolveTarget(targetArg).thenCompose(targetId -> {
            if (player.getUniqueId().equals(targetId)) {
                throw new IllegalArgumentException("You cannot add yourself.");
            }
            Map<String, Object> metadata = Map.of("actorName", player.getUsername());
            return friendService.sendInvite(player.getUniqueId(), targetId, metadata)
                    .thenApply(result -> Map.entry(targetId, result));
        }).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        logFailure("send friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        sendFramed(player, error(result.errorMessage().orElse("Unable to send request.")));
                        return;
                    }
                    String name = cachedName(entry.getKey());
                    sendFramed(player, FriendTextFormatter.green("Friend request sent to " + name + "."));
                }));
    }

    private void handleAccept(Player player, String targetArg) {
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.acceptInvite(player.getUniqueId(), targetId)
                        .thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        logFailure("accept friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        sendFramed(player, error(result.errorMessage().orElse("Unable to accept request.")));
                        return;
                    }
                    sendFramed(player, FriendTextFormatter.green("You are now friends with " + cachedName(entry.getKey()) + "."));
                }));
    }

    private void handleDeny(Player player, String targetArg) {
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.execute(FriendMutationRequest.builder(FriendMutationType.INVITE_DECLINE)
                        .actor(player.getUniqueId())
                        .target(targetId)
                        .build()).thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        logFailure("deny friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        sendFramed(player, error(result.errorMessage().orElse("Unable to deny request.")));
                        return;
                    }
                    sendFramed(player, FriendTextFormatter.gray("Declined request from " + cachedName(entry.getKey()) + "."));
                }));
    }

    private void handleRemove(Player player, String targetArg) {
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.unfriend(player.getUniqueId(), targetId)
                        .thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        logFailure("remove friend", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        sendFramed(player, error(result.errorMessage().orElse("Unable to remove friend.")));
                        return;
                    }
                    sendFramed(player, FriendTextFormatter.gray("Removed " + cachedName(entry.getKey()) + " from your friends."));
                }));
    }

    private void handleRemoveAll(Player player) {
        withSnapshot(player, true, snapshot -> {
            if (snapshot.friends().isEmpty()) {
                sendFramed(player, FriendTextFormatter.gray("You do not have any friends to remove."));
                return;
            }
            List<CompletionStage<FriendOperationResult>> futures = snapshot.friends().stream()
                    .map(friend -> friendService.unfriend(player.getUniqueId(), friend))
                    .toList();
            CompletableFuture.allOf(futures.stream()
                            .map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, throwable) ->
                            schedule(() -> {
                                if (throwable != null) {
                                    logFailure("remove all friends", player, throwable);
                                    sendFramed(player, error("Failed to remove all friends."));
                                    return;
                                }
                                sendFramed(player, FriendTextFormatter.gray("Removed " + snapshot.friends().size() + " friends."));
                            }));
        });
    }

    private void handleNotifications(Player player) {
        if (playerCache == null) {
            sendFramed(player, error("Notifications are unavailable right now."));
            return;
        }
        PlayerCache.CachedDocument doc = playerCache.root(player.getUniqueId());
        doc.getAsync("social.friendNotifications", Boolean.class)
                .thenCompose(optional -> {
                    boolean current = optional.orElse(true);
                    boolean next = !current;
                    return doc.setAsync("social.friendNotifications", next)
                            .thenApply(ignored -> next);
                })
                .whenComplete((enabled, throwable) ->
                        schedule(() -> {
                            if (throwable != null) {
                                logFailure("toggle friend notifications", player, throwable);
                                sendFramed(player, error("Unable to toggle notifications right now."));
                                return;
                            }
                            String state = enabled ? "enabled" : "disabled";
                            sendFramed(player, FriendTextFormatter.gray("Friend notifications " + state + "."));
                        }));
    }

    private void withSnapshot(Player player, boolean forceReload, Consumer<FriendSnapshot> consumer) {
        friendService.getSnapshot(player.getUniqueId(), forceReload)
                .whenComplete((snapshot, throwable) ->
                        schedule(() -> {
                            if (throwable != null || snapshot == null) {
                                if (throwable != null) {
                                    logFailure("load friend snapshot", player, throwable);
                                }
                                sendFramed(player, error("Unable to load your friend data."));
                                return;
                            }
                            consumer.accept(snapshot);
                        }));
    }

    private void requireArgument(Player player, String[] args, int size, Runnable action) {
        if (args.length < size) {
            sendFramed(player, error("Usage: /friend " + args[0] + " <player>"));
            return;
        }
        action.run();
    }

    private CompletionStage<UUID> resolveTarget(String targetArg) {
        UUID parsed = tryParseUuid(targetArg);
        if (parsed != null) {
            return CompletableFuture.completedFuture(parsed);
        }

        Optional<Player> online = proxy.getPlayer(targetArg);
        if (online.isPresent()) {
            UUID id = online.get().getUniqueId();
            cacheName(id, online.get().getUsername());
            return CompletableFuture.completedFuture(id);
        }

        UUID cached = reverseNameCache.get(targetArg.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<UUID> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalArgumentException("Player '" + targetArg + "' must be online or specified by UUID."));
        return future;
    }

    private void cacheName(UUID playerId, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        nameCache.put(playerId, name);
        reverseNameCache.put(name.toLowerCase(Locale.ROOT), playerId);
    }

    private List<String> snapshotNames(Player player) {
        return Collections.emptyList();
    }

    private String cachedName(UUID uuid) {
        return nameCache.getOrDefault(uuid, shortUuid(uuid));
    }

    private Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    private void sendFramed(Player player, Component line) {
        sendFramed(player, List.of(line));
    }

    private void sendFramed(Player player, Collection<Component> lines) {
        player.sendMessage(FRAME_LINE);
        lines.forEach(player::sendMessage);
        player.sendMessage(FRAME_LINE);
    }

    private void logFailure(String action, Player player, Throwable throwable) {
        logger.warn("Failed to {} for {} ({})", action, player.getUsername(), player.getUniqueId(), throwable);
    }

    private void schedule(Runnable runnable) {
        proxy.getScheduler().buildTask(plugin, runnable).schedule();
    }
}
