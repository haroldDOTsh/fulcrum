package sh.harold.fulcrum.velocity.friends;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.*;
import sh.harold.fulcrum.api.player.PlayerDirectory;
import sh.harold.fulcrum.api.player.PlayerDirectory.PlayerProfile;
import sh.harold.fulcrum.api.player.PlayerDirectory.ProfileQuery;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.status.PlayerStatus;
import sh.harold.fulcrum.api.status.PresenceStatus;
import sh.harold.fulcrum.common.cache.PlayerCache;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.fundamentals.routing.PlayerRoutingFeature;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class FriendCommand implements SimpleCommand {

    private static final int PAGE_SIZE = 8;
    private static final int FRAME_WIDTH = 53;
    private static final Duration INVITE_TIMEOUT = Duration.ofMinutes(5);

    private final FriendService friendService;
    private final ProxyServer proxy;
    private final FulcrumVelocityPlugin plugin;
    private final PlayerDirectory playerDirectory;
    private final PlayerCache playerCache;
    private final RankService rankService;
    private final PlayerRoutingFeature routingFeature;
    private final Logger logger;
    private final Map<UUID, Map<UUID, ScheduledTask>> inviteExpiryTasks = new ConcurrentHashMap<>();

    public FriendCommand(FriendService friendService,
                         ProxyServer proxy,
                         FulcrumVelocityPlugin plugin,
                         PlayerDirectory playerDirectory,
                         PlayerCache playerCache,
                         RankService rankService,
                         PlayerRoutingFeature routingFeature,
                         Logger logger) {
        this.friendService = Objects.requireNonNull(friendService, "friendService");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerDirectory = Objects.requireNonNull(playerDirectory, "playerDirectory");
        this.playerCache = playerCache;
        this.rankService = Objects.requireNonNull(rankService, "rankService");
        this.routingFeature = Objects.requireNonNull(routingFeature, "routingFeature");
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
        debug(player, "received /friend with args={}", Arrays.toString(args));
        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1 && tryHandleShorthandAdd(player, sub)) {
            return;
        }
        debug(player, "dispatching subcommand={}", sub);
        switch (sub) {
            case "help" -> sendHelp(player);
            case "list" -> handleList(player, args);
            case "requests" -> handleRequests(player, args);
            case "add" -> requireArgument(player, args, 2, () -> handleAdd(player, args[1]));
            case "accept" -> requireArgument(player, args, 2, () -> handleAccept(player, args[1]));
            case "deny" -> requireArgument(player, args, 2, () -> handleDeny(player, args[1]));
            case "ignore" -> requireArgument(player, args, 2, () -> handleIgnore(player, args[1]));
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

    private boolean tryHandleShorthandAdd(Player player, String possibleName) {
        if (possibleName == null || possibleName.isBlank()) {
            return false;
        }
        if (subcommands().contains(possibleName)) {
            return false;
        }
        handleAdd(player, possibleName);
        return true;
    }

    private void sendHelp(Player player) {
        List<Component> lines = List.of(
                FriendTextFormatter.aqua("/friend accept <player> ").append(FriendTextFormatter.yellow("Accept a friend request")),
                FriendTextFormatter.aqua("/friend add <player> ").append(FriendTextFormatter.yellow("Add a player as a friend")),
                FriendTextFormatter.aqua("/friend deny <player> ").append(FriendTextFormatter.yellow("Decline a friend request")),
                FriendTextFormatter.aqua("/friend help ").append(FriendTextFormatter.yellow("Show all available friend commands")),
                FriendTextFormatter.aqua("/friend list <page|best> ").append(FriendTextFormatter.yellow("List your friends")),
                FriendTextFormatter.aqua("/friend notifications ").append(FriendTextFormatter.yellow("Toggle friend join/leave notifications")),
                FriendTextFormatter.aqua("/friend remove <player> ").append(FriendTextFormatter.yellow("Remove a player from your friends")),
                FriendTextFormatter.aqua("/friend removeall ").append(FriendTextFormatter.yellow("Remove all friends")),
                FriendTextFormatter.aqua("/friend requests <page> ").append(FriendTextFormatter.yellow("View friend requests"))
        );
        sendFramed(player, lines);
    }

    private void handleList(Player player, String[] args) {
        debug(player, "handling list request args={}", Arrays.toString(args));
        if (args.length > 1 && "best".equalsIgnoreCase(args[1])) {
            sendFramed(player, FriendTextFormatter.yellow("Best friends will be available soon."));
            return;
        }
        final int requestedPage = args.length > 1 ? parsePage(args[1]) : 1;
        withSnapshot(player, false, snapshot -> {
            List<UUID> friends = new ArrayList<>(snapshot.friendIds());
            if (friends.isEmpty()) {
                sendFramed(player, FriendTextFormatter.yellow("You have no friends yet. Use /friend add <player> to get started."));
                return;
            }
            playerDirectory.getProfiles(friends, ProfileQuery.DEFAULT)
                    .thenCombine(playerDirectory.getStatuses(friends), (profiles, statuses) -> Map.entry(profiles, statuses))
                    .whenComplete((result, throwable) -> schedule(() -> {
                        Map<UUID, PlayerProfile> profiles = result != null ? result.getKey() : null;
                        Map<UUID, PlayerStatus> statuses = result != null && result.getValue() != null
                                ? result.getValue()
                                : Map.of();
                        if (throwable != null || profiles == null) {
                            sendFramed(player, error("Unable to load your friend directory."));
                            return;
                        }
                        friends.sort(Comparator.comparing(uuid -> displayName(profiles.get(uuid)).toLowerCase(Locale.ROOT)));
                        int totalPages = Math.max(1, (int) Math.ceil(friends.size() / (double) PAGE_SIZE));
                        int page = Math.min(Math.max(requestedPage, 1), totalPages);
                        int start = (page - 1) * PAGE_SIZE;
                        List<UUID> slice = friends.subList(start, Math.min(start + PAGE_SIZE, friends.size()));
                        List<Component> lines = new ArrayList<>();
                        lines.add(renderListHeader(page, totalPages));
                        debug(player, "rendering friend list page={} totalPages={} totalFriends={}", page, totalPages, friends.size());
                        for (UUID id : slice) {
                            PlayerProfile profile = profiles.getOrDefault(id, PlayerProfile.missing(id));
                            PlayerStatus status = statuses.get(id);
                            lines.add(renderFriendPresence(id, profile, status));
                        }
                        sendFramed(player, lines);
                    }));
        });
    }

    private void handleRequests(Player player, String[] args) {
        debug(player, "handling requests args={}", Arrays.toString(args));
        final int requestedPage = args.length > 1 ? parsePage(args[1]) : 1;
        friendService.getPendingInvites(player.getUniqueId())
                .whenComplete((invites, throwable) -> {
                    if (throwable != null) {
                        schedule(() -> {
                            debug(player, "pending invite fetch failed error={}", throwable.toString());
                            sendFramed(player, error("Unable to load your friend requests."));
                        });
                        return;
                    }
                    if (invites == null || invites.isEmpty()) {
                        schedule(() -> sendFramed(player, FriendTextFormatter.yellow("You do not have any pending requests.")));
                        return;
                    }
                    List<FriendService.PendingFriendInvite> sorted = new ArrayList<>(invites);
                    sorted.sort(Comparator.comparingLong(FriendService.PendingFriendInvite::requestedAtEpochMillis).reversed());
                    int totalPages = Math.max(1, (int) Math.ceil(sorted.size() / (double) PAGE_SIZE));
                    int page = Math.min(Math.max(requestedPage, 1), totalPages);
                    int start = (page - 1) * PAGE_SIZE;
                    List<FriendService.PendingFriendInvite> slice = sorted.subList(start, Math.min(start + PAGE_SIZE, sorted.size()));
                    List<UUID> actorIds = slice.stream().map(FriendService.PendingFriendInvite::actorId).toList();
                    playerDirectory.getProfiles(actorIds, ProfileQuery.DEFAULT)
                            .whenComplete((profiles, profileThrowable) ->
                                    schedule(() -> {
                                        if (profileThrowable != null || profiles == null) {
                                            sendFramed(player, error("Unable to load your friend requests."));
                                            return;
                                        }
                                        List<Component> lines = new ArrayList<>();
                                        lines.add(FriendTextFormatter.aqua("Incoming requests (" + sorted.size() + ") - Page " + page + "/" + totalPages));
                                        debug(player, "rendering request list page={} totalPages={} totalRequests={}", page, totalPages, sorted.size());
                                        for (FriendService.PendingFriendInvite invite : slice) {
                                            UUID actorId = invite.actorId();
                                            PlayerProfile profile = profiles.getOrDefault(actorId, PlayerProfile.missing(actorId));
                                            Component line = Component.text()
                                                    .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                                                    .append(profile.formattedName())
                                                    .append(Component.text(" - "))
                                                    .append(Component.text("Use /friend accept ", NamedTextColor.YELLOW))
                                                    .append(profile.formattedName())
                                                    .build();
                                            lines.add(line);
                                        }
                                        sendFramed(player, lines);
                                    }));
                });
    }

    private void handleAdd(Player player, String targetArg) {
        debug(player, "handling add targetArg={}", targetArg);
        resolveTarget(targetArg).whenComplete((targetId, throwable) -> {
            if (throwable != null) {
                debug(player, "add target resolution failed targetArg={} error={}", targetArg, throwable.getMessage());
                schedule(() -> sendFramed(player, error(messageFromThrowable(throwable))));
                return;
            }
            if (player.getUniqueId().equals(targetId)) {
                schedule(() -> sendFramed(player, error("You can't add yourself as a friend!")));
                return;
            }
            Map<String, Object> metadata = Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, player.getUsername());
            debug(player, "sending invite actor={} target={} metadata={}", player.getUniqueId(), targetId, metadata);
            friendService.sendInvite(player.getUniqueId(), targetId, metadata)
                    .thenApply(result -> Map.entry(targetId, result))
                    .whenComplete((entry, mutationThrowable) ->
                            schedule(() -> {
                                if (mutationThrowable != null) {
                                    logFailure("send friend request", player, mutationThrowable);
                                    debug(player, "friend invite failed target={} error={}", targetId, mutationThrowable.toString());
                                    sendFramed(player, error(messageFromThrowable(mutationThrowable)));
                                    return;
                                }
                                FriendOperationResult result = entry.getValue();
                                if (!result.success()) {
                                    debug(player, "friend invite rejected target={} error={}", entry.getKey(), result.errorMessage().orElse("unknown"));
                                    String fallback = "You've already sent a friend request to this person!";
                                    sendFramed(player, error(result.errorMessage().orElse(fallback)));
                                    return;
                                }
                                debug(player, "friend invite completed target={} actorSnapshotVersion={} targetSnapshotVersion={}",
                                        entry.getKey(),
                                        result.actorSnapshot() != null ? result.actorSnapshot().version() : 0L,
                                        result.targetSnapshot() != null ? result.targetSnapshot().version() : 0L);
                                boolean friendshipEstablished = result.actorSnapshot() != null
                                        && result.actorSnapshot().friendIds().contains(entry.getKey());
                                withProfile(entry.getKey(), profile -> {
                                    Component body;
                                    if (friendshipEstablished) {
                                        body = Component.text()
                                                .append(FriendTextFormatter.green("You are now friends with "))
                                                .append(profile.formattedName())
                                                .append(FriendTextFormatter.green("."))
                                                .build();
                                    } else {
                                        body = Component.text()
                                                .append(FriendTextFormatter.yellow("You sent a friend request to "))
                                                .append(profile.formattedName())
                                                .append(FriendTextFormatter.yellow("! They have 5 minutes to accept it!"))
                                                .build();
                                    }
                                    sendFramed(player, body);
                                });
                                if (!friendshipEstablished) {
                                    scheduleInviteExpiryReminder(player.getUniqueId(), entry.getKey());
                                }
                            }));
        });
    }

    private void handleAccept(Player player, String targetArg) {
        debug(player, "handling accept targetArg={}", targetArg);
        resolveTarget(targetArg).thenCompose(targetId -> {
            Map<String, Object> metadata = Map.of(FriendMutationRequest.METADATA_ACTOR_NAME, player.getUsername());
            return friendService.acceptInvite(player.getUniqueId(), targetId, metadata)
                    .thenApply(result -> Map.entry(targetId, result));
        }).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        debug(player, "accept flow failed targetArg={} error={}", targetArg, throwable.toString());
                        logFailure("accept friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        debug(player, "accept rejected target={} error={}", entry.getKey(), result.errorMessage().orElse("unknown"));
                        if (isMissingInviteError(result)) {
                            sendFramed(player, missingInviteMessage(entry.getKey()));
                        } else {
                            sendFramed(player, error(result.errorMessage().orElse("Unable to accept request.")));
                        }
                        return;
                    }
                    debug(player, "accept completed target={} relationVersionActor={} relationVersionTarget={}",
                            entry.getKey(),
                            result.actorSnapshot() != null ? result.actorSnapshot().version() : 0L,
                            result.targetSnapshot() != null ? result.targetSnapshot().version() : 0L);
                    withProfile(entry.getKey(), profile -> {
                        Component success = Component.text()
                                .append(FriendTextFormatter.green("You are now friends with "))
                                .append(profile.formattedName())
                                .append(FriendTextFormatter.green("."))
                                .build();
                        sendFramed(player, success);
                    });
                }));
    }

    private void handleDeny(Player player, String targetArg) {
        debug(player, "handling deny targetArg={}", targetArg);
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.execute(FriendMutationRequest.builder(FriendMutationType.INVITE_DECLINE)
                        .actor(player.getUniqueId())
                        .target(targetId)
                        .build()).thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        debug(player, "deny flow failed targetArg={} error={}", targetArg, throwable.toString());
                        logFailure("deny friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        debug(player, "deny rejected target={} error={}", entry.getKey(), result.errorMessage().orElse("unknown"));
                        if (isMissingInviteError(result)) {
                            sendFramed(player, missingInviteMessage(entry.getKey()));
                        } else {
                            sendFramed(player, error(result.errorMessage().orElse("Unable to deny request.")));
                        }
                        return;
                    }
                    debug(player, "deny completed target={}", entry.getKey());
                }));
    }

    private void handleIgnore(Player player, String targetArg) {
        debug(player, "handling ignore targetArg={}", targetArg);
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.declineInvite(player.getUniqueId(), targetId)
                        .thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        debug(player, "ignore flow failed targetArg={} error={}", targetArg, throwable.toString());
                        logFailure("ignore friend request", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        debug(player, "ignore rejected target={} error={}", entry.getKey(), result.errorMessage().orElse("unknown"));
                        if (isMissingInviteError(result)) {
                            sendFramed(player, missingInviteMessage(entry.getKey()));
                        } else {
                            sendFramed(player, error(result.errorMessage().orElse("Unable to ignore request.")));
                        }
                        return;
                    }
                    debug(player, "ignore completed target={}", entry.getKey());
                    withProfile(entry.getKey(), profile -> {
                        Component success = Component.text()
                                .append(FriendTextFormatter.yellow("Ignored request from "))
                                .append(profile.formattedName())
                                .append(FriendTextFormatter.yellow("."))
                                .build();
                        sendFramed(player, success);
                    });
                }));
    }

    private void handleRemove(Player player, String targetArg) {
        debug(player, "handling remove targetArg={}", targetArg);
        resolveTarget(targetArg).thenCompose(targetId ->
                friendService.unfriend(player.getUniqueId(), targetId)
                        .thenApply(result -> Map.entry(targetId, result))
        ).whenComplete((entry, throwable) ->
                schedule(() -> {
                    if (throwable != null) {
                        debug(player, "remove flow failed targetArg={} error={}", targetArg, throwable.toString());
                        logFailure("remove friend", player, throwable);
                        sendFramed(player, error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        debug(player, "remove rejected target={} error={}", entry.getKey(), result.errorMessage().orElse("unknown"));
                        sendFramed(player, error(result.errorMessage().orElse("Unable to remove friend.")));
                        return;
                    }
                    debug(player, "remove completed target={}", entry.getKey());
                    withProfile(entry.getKey(), profile -> {
                        Component info = Component.text()
                                .append(FriendTextFormatter.yellow("Removed "))
                                .append(profile.formattedName())
                                .append(FriendTextFormatter.yellow(" from your friends list!"))
                                .build();
                        sendFramed(player, info);
                    });
                }));
    }

    private void handleRemoveAll(Player player) {
        debug(player, "handling remove all");
        withSnapshot(player, true, snapshot -> {
            if (snapshot.friendIds().isEmpty()) {
                debug(player, "remove all skipped - no friends");
                sendFramed(player, FriendTextFormatter.yellow("You do not have any friends to remove."));
                return;
            }
            int totalFriends = snapshot.friendIds().size();
            debug(player, "removing {} friends", totalFriends);
            List<CompletionStage<FriendOperationResult>> futures = snapshot.friendIds().stream()
                    .map(friend -> friendService.unfriend(player.getUniqueId(), friend))
                    .toList();
            CompletableFuture.allOf(futures.stream()
                            .map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, throwable) ->
                            schedule(() -> {
                                if (throwable != null) {
                                    debug(player, "remove all failed error={}", throwable.toString());
                                    logFailure("remove all friends", player, throwable);
                                    sendFramed(player, error("Failed to remove all friends."));
                                    return;
                                }
                                debug(player, "remove all succeeded removedCount={}", totalFriends);
                                sendFramed(player, FriendTextFormatter.yellow("Removed " + totalFriends + " friends."));
                            }));
        });
    }

    private void handleNotifications(Player player) {
        debug(player, "handling notifications toggle");
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
                                debug(player, "notification toggle failed error={}", throwable.toString());
                                logFailure("toggle friend notifications", player, throwable);
                                sendFramed(player, error("Unable to toggle notifications right now."));
                                return;
                            }
                            String state = enabled ? "enabled" : "disabled";
                            debug(player, "notification toggle complete state={}", state);
                            sendFramed(player, FriendTextFormatter.yellow("Friend notifications " + state + "."));
                        }));
    }

    private void withSnapshot(Player player, boolean forceReload, Consumer<FriendSnapshot> consumer) {
        debug(player, "loading snapshot forceReload={}", forceReload);
        friendService.getSnapshot(player.getUniqueId(), forceReload)
                .whenComplete((snapshot, throwable) ->
                        schedule(() -> {
                            if (throwable != null || snapshot == null) {
                                if (throwable != null) {
                                    logFailure("load friend snapshot", player, throwable);
                                    debug(player, "snapshot load failed forceReload={} error={}", forceReload, throwable.toString());
                                }
                                sendFramed(player, error("Unable to load your friend data."));
                                return;
                            }
                            debug(player, "snapshot ready version={} friends={}",
                                    snapshot.version(),
                                    snapshot.friendIds().size());
                            consumer.accept(snapshot);
                        }));
    }

    private void requireArgument(Player player, String[] args, int size, Runnable action) {
        if (args.length < size) {
            debug(player, "missing arguments subcommand={} providedArgs={}", args[0], Arrays.toString(args));
            sendFramed(player, error("Usage: /friend " + args[0] + " <player>"));
            return;
        }
        action.run();
    }

    private CompletionStage<UUID> resolveTarget(String targetArg) {
        log("Resolving target argument {}", targetArg);
        UUID parsed = tryParseUuid(targetArg);
        if (parsed != null) {
            log("Target argument {} parsed as UUID {}", targetArg, parsed);
            return CompletableFuture.completedFuture(parsed);
        }

        Optional<Player> online = proxy.getPlayer(targetArg);
        if (online.isPresent()) {
            UUID id = online.get().getUniqueId();
            log("Resolved {} to online player {}", targetArg, id);
            return CompletableFuture.completedFuture(id);
        }

        return playerDirectory.findProfileByName(targetArg, ProfileQuery.DEFAULT)
                .thenCompose(optional -> optional
                        .map(profile -> {
                            log("Resolved {} using directory to {}", targetArg, profile.playerId());
                            return CompletableFuture.completedFuture(profile.playerId());
                        })
                        .orElseGet(() -> {
                            CompletableFuture<UUID> failure = new CompletableFuture<>();
                            failure.completeExceptionally(new IllegalArgumentException("Player '" + targetArg + "' must be online or specified by UUID."));
                            log("Unable to resolve target argument {}", targetArg);
                            return failure;
                        }));
    }

    private List<String> snapshotNames(Player player) {
        return Collections.emptyList();
    }

    private Component renderListHeader(int page, int totalPages) {
        boolean hasPrev = page > 1;
        boolean hasNext = page < totalPages;
        Component leftArrow = Component.text("«", hasPrev ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.BOLD);
        Component rightArrow = Component.text("»", hasNext ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.BOLD);
        Component spacer = Component.text(" ", NamedTextColor.YELLOW);
        String label = "Friends (Page " + page + " of " + totalPages + ")";
        Component middle = Component.text(label, NamedTextColor.GOLD);
        Component row = Component.text()
                .append(leftArrow)
                .append(spacer)
                .append(middle)
                .append(spacer)
                .append(rightArrow)
                .build();
        int contentLength = label.length() + 4;
        int totalPadding = Math.max(0, FRAME_WIDTH - contentLength);
        int leftPadding = totalPadding / 2;
        int rightPadding = totalPadding - leftPadding;
        Component leftPad = Component.text(" ".repeat(leftPadding));
        Component rightPad = Component.text(" ".repeat(rightPadding));
        return Component.text()
                .append(leftPad)
                .append(row)
                .append(rightPad)
                .build();
    }

    private Component renderFriendPresence(UUID friendId) {
        return renderFriendPresence(friendId, PlayerProfile.missing(friendId), null);
    }

    private Component renderFriendPresence(UUID friendId, PlayerProfile profile, PlayerStatus status) {
        Component name = profile != null ? profile.formattedName() : FriendTextFormatter.formatName(friendId, shortUuid(friendId), rankService, logger);
        PresenceStatus presence = status != null ? status.presence() : PresenceStatus.OFFLINE;
        NamedTextColor dotColor = switch (presence) {
            case ONLINE -> NamedTextColor.GREEN;
            case AWAY -> NamedTextColor.GOLD;
            case BUSY -> NamedTextColor.RED;
            case FAKE_OFFLINE, OFFLINE -> NamedTextColor.GRAY;
        };
        Component dot = Component.text("● ", dotColor);

        if (presence == PresenceStatus.OFFLINE || presence == PresenceStatus.FAKE_OFFLINE) {
            return Component.text()
                    .append(dot)
                    .append(name)
                    .append(FriendTextFormatter.yellow(" is currently offline"))
                    .build();
        }

        Optional<Player> online = proxy.getPlayer(friendId);
        if (online.isEmpty()) {
            return Component.text()
                    .append(dot)
                    .append(name)
                    .append(FriendTextFormatter.yellow(" is currently offline"))
                    .build();
        }
        Player player = online.get();
        String familyLabel = resolveFamilyLabel(friendId, player);
        if (familyLabel == null || familyLabel.isBlank()) {
            familyLabel = "unknown";
        }
        return Component.text()
                .append(dot)
                .append(name)
                .append(FriendTextFormatter.yellow(" is in "))
                .append(Component.text(familyLabel, NamedTextColor.GOLD))
                .build();
    }

    private String resolveFamilyLabel(UUID friendId, Player player) {
        Optional<PlayerRoutingFeature.PlayerLocationSnapshot> snapshot = routingFeature.getPlayerLocation(friendId);
        if (snapshot.isPresent()) {
            PlayerRoutingFeature.PlayerLocationSnapshot data = snapshot.get();
            String familyId = data.getFamilyId();
            if (familyId != null && !familyId.isBlank()) {
                return familyId;
            }
            String serverId = data.getServerId();
            String slotSuffix = data.getSlotSuffix();
            if (serverId != null && !serverId.isBlank()) {
                return slotSuffix != null && !slotSuffix.isBlank()
                        ? serverId + slotSuffix
                        : serverId;
            }
        }
        return player.getCurrentServer()
                .map(current -> current.getServerInfo().getName())
                .orElse("unknown");
    }

    private Component missingInviteMessage(UUID targetId) {
        Component targetName = PlayerProfile.missing(targetId).formattedName();
        return Component.text()
                .append(FriendTextFormatter.yellow("You don't have an invite from "))
                .append(targetName)
                .append(FriendTextFormatter.yellow("."))
                .build();
    }

    private boolean isMissingInviteError(FriendOperationResult result) {
        return result != null && result.errorMessage()
                .map(message -> message.toLowerCase(Locale.ROOT).contains("no pending invite"))
                .orElse(false);
    }

    private Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    private void withProfile(UUID playerId, Consumer<PlayerProfile> consumer) {
        if (playerId == null) {
            schedule(() -> consumer.accept(PlayerProfile.missing(null)));
            return;
        }
        playerDirectory.getProfile(playerId)
                .whenComplete((profile, throwable) ->
                        schedule(() -> {
                            PlayerProfile resolved = (throwable == null && profile != null)
                                    ? profile
                                    : PlayerProfile.missing(playerId);
                            consumer.accept(resolved);
                        }));
    }

    private void sendFramed(Player player, Component line) {
        FriendMessageRenderer.sendFramed(player, line);
    }

    private void sendFramed(Player player, Collection<Component> lines) {
        FriendMessageRenderer.sendFramed(player, lines);
    }

    private void debug(Player player, String message, Object... args) {
        // intentionally silent
    }

    private void log(String message, Object... args) {
        // intentionally silent
    }

    private void logFailure(String action, Player player, Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            return;
        }
        logger.warn("Failed to {} for {} ({})", action, player.getUsername(), player.getUniqueId(), throwable);
    }

    private void schedule(Runnable runnable) {
        proxy.getScheduler().buildTask(plugin, runnable).schedule();
    }

    private void scheduleInviteExpiryReminder(UUID actorId, UUID targetId) {
        if (actorId == null || targetId == null) {
            return;
        }
        cancelInviteExpiryReminder(actorId, targetId);
        ScheduledTask task = proxy.getScheduler()
                .buildTask(plugin, () ->
                        friendService.getSnapshot(actorId, true)
                                .whenComplete((snapshot, throwable) -> {
                                    removeInviteExpiryTask(actorId, targetId);
                                    if (throwable != null || snapshot == null) {
                                        return;
                                    }
                                    if (snapshot.friendIds().contains(targetId)) {
                                        return;
                                    }
                                    proxy.getPlayer(actorId).ifPresent(player ->
                                            playerDirectory.getProfile(targetId).whenComplete((profile, error) ->
                                                    schedule(() -> {
                                                        PlayerProfile resolved = (error == null && profile != null)
                                                                ? profile
                                                                : PlayerProfile.missing(targetId);
                                                        Component message = Component.text()
                                                                .append(FriendTextFormatter.yellow("Your friend request to "))
                                                                .append(resolved.formattedName())
                                                                .append(FriendTextFormatter.yellow(" has expired."))
                                                                .build();
                                                        sendFramed(player, message);
                                                    })));
                                }))
                .delay(INVITE_TIMEOUT)
                .schedule();
        inviteExpiryTasks.compute(actorId, (key, map) -> {
            Map<UUID, ScheduledTask> tasks = map != null ? map : new ConcurrentHashMap<>();
            tasks.put(targetId, task);
            return tasks;
        });
    }

    private String displayName(PlayerProfile profile) {
        if (profile != null && profile.username() != null && !profile.username().isBlank()) {
            return profile.username();
        }
        if (profile != null && profile.playerId() != null) {
            return profile.playerId().toString().split("-")[0];
        }
        return "unknown";
    }

    private void cancelInviteExpiryReminder(UUID actorId, UUID targetId) {
        inviteExpiryTasks.computeIfPresent(actorId, (key, map) -> {
            ScheduledTask task = map.remove(targetId);
            if (task != null) {
                task.cancel();
            }
            return map.isEmpty() ? null : map;
        });
    }

    private void removeInviteExpiryTask(UUID actorId, UUID targetId) {
        inviteExpiryTasks.computeIfPresent(actorId, (key, map) -> {
            map.remove(targetId);
            return map.isEmpty() ? null : map;
        });
    }
}
