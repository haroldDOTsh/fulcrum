package sh.harold.fulcrum.velocity.friends;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.friends.FriendOperationResult;
import sh.harold.fulcrum.api.friends.FriendService;
import sh.harold.fulcrum.api.player.PlayerDirectory;
import sh.harold.fulcrum.api.player.PlayerDirectory.PlayerProfile;
import sh.harold.fulcrum.api.player.PlayerDirectory.ProfileQuery;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Velocity command that lets players manage ignores (blocks) without opening the friend UI.
 */
public final class IgnoreCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "list");

    private final FriendService friendService;
    private final RankService rankService;
    private final ProxyServer proxy;
    private final FulcrumVelocityPlugin plugin;
    private final PlayerDirectory playerDirectory;
    private final Logger logger;

    public IgnoreCommand(FriendService friendService,
                         RankService rankService,
                         ProxyServer proxy,
                         FulcrumVelocityPlugin plugin,
                         PlayerDirectory playerDirectory,
                         Logger logger) {
        this.friendService = Objects.requireNonNull(friendService, "friendService");
        this.rankService = Objects.requireNonNull(rankService, "rankService");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerDirectory = Objects.requireNonNull(playerDirectory, "playerDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private static UUID tryParseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String messageFromThrowable(Throwable throwable) {
        if (throwable == null) {
            return "Request failed.";
        }
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
            source.sendMessage(Component.text("Only players can manage ignores.", NamedTextColor.RED));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            handleList(player);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> requireArgument(player, args, 2, () -> handleAdd(player, args[1]));
            case "remove" -> requireArgument(player, args, 2, () -> handleRemove(player, args[1]));
            case "list" -> handleList(player);
            default -> sendUsage(player);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(cmd -> cmd.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && ("add".equalsIgnoreCase(args[0]) || "remove".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return proxy.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private void handleAdd(Player player, String targetArg) {
        UUID actorId = player.getUniqueId();
        resolveTarget(targetArg)
                .thenCompose(targetId -> validateNotSelf(actorId, targetId))
                .thenCompose(this::enforceStaffExemption)
                .thenCompose(targetId -> friendService.block(actorId, targetId, null, "ignore-command")
                        .thenApply(result -> Map.entry(targetId, result)))
                .whenComplete((entry, throwable) -> schedule(() -> {
                    if (throwable != null) {
                        logFailure("apply ignore", player, throwable);
                        FriendMessageRenderer.sendFramed(player, FriendMessageRenderer.error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        FriendMessageRenderer.sendFramed(player,
                                FriendMessageRenderer.error(result.errorMessage().orElse("Unable to ignore that player.")));
                        return;
                    }
                    withProfile(entry.getKey(), profile -> {
                        Component line = Component.text()
                                .append(FriendTextFormatter.green("You are now ignoring "))
                                .append(profile.formattedName())
                                .append(FriendTextFormatter.green("."))
                                .build();
                        FriendMessageRenderer.sendFramed(player, line);
                    });
                }));
    }

    private void handleRemove(Player player, String targetArg) {
        UUID actorId = player.getUniqueId();
        resolveTarget(targetArg)
                .thenCompose(targetId -> friendService.unblock(actorId, targetId)
                        .thenApply(result -> Map.entry(targetId, result)))
                .whenComplete((entry, throwable) -> schedule(() -> {
                    if (throwable != null) {
                        logFailure("remove ignore", player, throwable);
                        FriendMessageRenderer.sendFramed(player, FriendMessageRenderer.error(messageFromThrowable(throwable)));
                        return;
                    }
                    FriendOperationResult result = entry.getValue();
                    if (!result.success()) {
                        FriendMessageRenderer.sendFramed(player,
                                FriendMessageRenderer.error(result.errorMessage().orElse("You are not ignoring that player.")));
                        return;
                    }
                    withProfile(entry.getKey(), profile -> {
                        Component line = Component.text()
                                .append(FriendTextFormatter.yellow("You are no longer ignoring "))
                                .append(profile.formattedName())
                                .append(FriendTextFormatter.yellow("."))
                                .build();
                        FriendMessageRenderer.sendFramed(player, line);
                    });
                }));
    }

    private void handleList(Player player) {
        friendService.getSnapshot(player.getUniqueId(), false)
                .whenComplete((snapshot, throwable) -> schedule(() -> {
                    if (throwable != null || snapshot == null) {
                        logFailure("list ignores", player, throwable);
                        FriendMessageRenderer.sendFramed(player, FriendMessageRenderer.error("Unable to load ignore data right now."));
                        return;
                    }
                    Collection<UUID> ignores = snapshot.ignoresOut();
                    if (ignores.isEmpty()) {
                        FriendMessageRenderer.sendFramed(player,
                                FriendMessageRenderer.info("You are not ignoring anyone. Use /ignore add <player>."));
                        return;
                    }
                    List<UUID> sorted = new ArrayList<>(ignores);
                    playerDirectory.getProfiles(sorted, ProfileQuery.DEFAULT)
                            .whenComplete((profiles, profileThrowable) ->
                                    schedule(() -> {
                                        if (profileThrowable != null || profiles == null) {
                                            FriendMessageRenderer.sendFramed(player, FriendMessageRenderer.error("Unable to load ignore data right now."));
                                            return;
                                        }
                                        sorted.sort(Comparator.comparing(id -> displayName(profiles.get(id)).toLowerCase(Locale.ROOT)));
                                        List<Component> lines = new ArrayList<>();
                                        lines.add(Component.text("Ignored players (" + sorted.size() + ")", NamedTextColor.GOLD, TextDecoration.BOLD));
                                        for (UUID id : sorted) {
                                            PlayerProfile profile = profiles.getOrDefault(id, PlayerProfile.missing(id));
                                            lines.add(renderEntry(id, profile));
                                        }
                                        FriendMessageRenderer.sendFramed(player, lines);
                                    }));
                }));
    }

    private Component renderEntry(UUID targetId, PlayerProfile profile) {
        Component name = profile.formattedName();
        String commandTarget = profile.exists() ? profile.username() : targetId.toString();
        Component remove = Component.text("[REMOVE]", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/ignore remove " + commandTarget))
                .hoverEvent(HoverEvent.showText(Component.text("Click to remove this ignore", NamedTextColor.RED)));
        return Component.text()
                .append(FriendTextFormatter.gray(" • "))
                .append(name)
                .append(Component.text(" "))
                .append(remove)
                .build();
    }

    private void requireArgument(Player player, String[] args, int size, Runnable action) {
        if (args.length < size) {
            FriendMessageRenderer.sendFramed(player,
                    FriendMessageRenderer.error("Usage: /ignore " + args[0] + " <player>"));
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
            Player player = online.get();
            return CompletableFuture.completedFuture(player.getUniqueId());
        }
        return playerDirectory.findProfileByName(targetArg, ProfileQuery.DEFAULT)
                .thenCompose(optional -> optional
                        .map(profile -> CompletableFuture.completedFuture(profile.playerId()))
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalArgumentException("Player '" + targetArg + "' is not online."))));
    }

    private CompletionStage<UUID> validateNotSelf(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("You cannot ignore yourself."));
        }
        return CompletableFuture.completedFuture(targetId);
    }

    private CompletionStage<UUID> enforceStaffExemption(UUID targetId) {
        return rankService.isStaff(targetId).thenCompose(isStaff -> {
            if (Boolean.TRUE.equals(isStaff)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Staff members cannot be ignored."));
            }
            return CompletableFuture.completedFuture(targetId);
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

    private void sendUsage(Player player) {
        Collection<Component> lines = List.of(
                FriendTextFormatter.aqua("/ignore add <player> ").append(FriendTextFormatter.yellow("Ignore a player.")),
                FriendTextFormatter.aqua("/ignore remove <player> ").append(FriendTextFormatter.yellow("Stop ignoring a player.")),
                FriendTextFormatter.aqua("/ignore list ").append(FriendTextFormatter.yellow("View ignored players."))
        );
        FriendMessageRenderer.sendFramed(player, lines);
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

    private void schedule(Runnable runnable) {
        proxy.getScheduler().buildTask(plugin, runnable).schedule();
    }

    private void logFailure(String action, Player player, Throwable throwable) {
        if (throwable == null || throwable instanceof IllegalArgumentException) {
            return;
        }
        logger.warn("Failed to {} for {} ({})", action, player.getUsername(), player.getUniqueId(), throwable);
    }
}
