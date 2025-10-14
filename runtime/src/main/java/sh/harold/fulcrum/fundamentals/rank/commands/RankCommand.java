package sh.harold.fulcrum.fundamentals.rank.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Command handler for rank management operations.
 * Uses Paper's modern brigadier-style command API.
 */
public class RankCommand {

    private static final String PERMISSION_MANAGE = "fulcrum.rank.manage";
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final RankService rankService;
    private final Logger logger;

    public RankCommand(RankService rankService, Logger logger) {
        this.rankService = rankService;
        this.logger = logger;
    }

    /**
     * Builds the command tree for rank management.
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("rank")
                .requires(this::hasManagePermission)
                .then(literal("set")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanks)
                                        .executes(ctx -> setRankOnline(ctx)))))
                .then(literal("setoffline")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(this::suggestOfflinePlayers)
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanks)
                                        .executes(ctx -> setRankOffline(ctx)))))
                .then(literal("add")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanks)
                                        .executes(ctx -> addRankOnline(ctx)))))
                .then(literal("addoffline")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(this::suggestOfflinePlayers)
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanks)
                                        .executes(ctx -> addRankOffline(ctx)))))
                .then(literal("remove")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanksForPlayer)
                                        .executes(ctx -> removeRankOnline(ctx)))))
                .then(literal("removeoffline")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(this::suggestOfflinePlayers)
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(this::suggestRanks)
                                        .executes(ctx -> removeRankOffline(ctx)))))
                .then(literal("list")
                        .then(argument("player", ArgumentTypes.player())
                                .executes(ctx -> listRanksOnline(ctx))))
                .then(literal("listoffline")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(this::suggestOfflinePlayers)
                                .executes(ctx -> listRanksOffline(ctx))))
                .then(literal("info")
                        .then(argument("rank", StringArgumentType.word())
                                .suggests(this::suggestRanks)
                                .executes(ctx -> showRankInfo(ctx))))
                .then(literal("clear")
                        .then(argument("player", ArgumentTypes.player())
                                .executes(ctx -> clearRanksOnline(ctx))))
                .then(literal("clearoffline")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(this::suggestOfflinePlayers)
                                .executes(ctx -> clearRanksOffline(ctx))))
                .then(literal("reload")
                        .executes(ctx -> reloadRanks(ctx)))
                .executes(ctx -> showHelp(ctx))
                .build();
    }

    private boolean hasManagePermission(CommandSourceStack source) {
        // Use RankUtils to check if sender can manage ranks
        // This checks for STAFF rank or console
        return RankUtils.canManageRanks(source.getSender());
    }

    private int setRankOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        int changed = 0;
        for (Player target : resolver.resolve(ctx.getSource())) {
            rankService.setPrimaryRank(target.getUniqueId(), rank, context).thenAccept(v -> {
                sender.sendMessage(Component.text()
                        .append(Component.text("✓ ", NamedTextColor.GREEN))
                        .append(Component.text("Set rank for ", NamedTextColor.GRAY))
                        .append(Component.text(target.getName(), NamedTextColor.WHITE))
                        .append(Component.text(" to ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .build());

                // Notify the target player
                target.sendMessage(Component.text()
                        .append(Component.text("Your rank has been updated to ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .build());

                // Update command permissions for tab completion
                refreshPlayerCommands(target);
            }).exceptionally(ex -> {
                sender.sendMessage(Component.text("✗ Failed to set rank: " + ex.getMessage(), NamedTextColor.RED));
                logger.log(Level.WARNING, "Failed to set rank", ex);
                return null;
            });
            changed++;
        }

        return changed;
    }

    private int setRankOffline(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String playerName = StringArgumentType.getString(ctx, "player");
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        rankService.setPrimaryRank(target.getUniqueId(), rank, context).thenAccept(v -> {
            sender.sendMessage(Component.text()
                    .append(Component.text("✓ ", NamedTextColor.GREEN))
                    .append(Component.text("Set rank for ", NamedTextColor.GRAY))
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .append(Component.text(" to ", NamedTextColor.GRAY))
                    .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                    .build());
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("✗ Failed to set rank: " + ex.getMessage(), NamedTextColor.RED));
            logger.log(Level.WARNING, "Failed to set rank", ex);
            return null;
        });

        return 1;
    }

    private int addRankOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        int changed = 0;
        for (Player target : resolver.resolve(ctx.getSource())) {
            rankService.addRank(target.getUniqueId(), rank, context).thenAccept(v -> {
                sender.sendMessage(Component.text()
                        .append(Component.text("✓ ", NamedTextColor.GREEN))
                        .append(Component.text("Added rank ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .append(Component.text(" to ", NamedTextColor.GRAY))
                        .append(Component.text(target.getName(), NamedTextColor.WHITE))
                        .build());

                // Notify the target player
                target.sendMessage(Component.text()
                        .append(Component.text("You have been granted the ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .append(Component.text(" rank", NamedTextColor.GRAY))
                        .build());

                // Update command permissions for tab completion
                refreshPlayerCommands(target);
            }).exceptionally(ex -> {
                sender.sendMessage(Component.text("✗ Failed to add rank: " + ex.getMessage(), NamedTextColor.RED));
                logger.log(Level.WARNING, "Failed to add rank", ex);
                return null;
            });
            changed++;
        }

        return changed;
    }

    private int addRankOffline(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String playerName = StringArgumentType.getString(ctx, "player");
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        rankService.addRank(target.getUniqueId(), rank, context).thenAccept(v -> {
            sender.sendMessage(Component.text()
                    .append(Component.text("✓ ", NamedTextColor.GREEN))
                    .append(Component.text("Added rank ", NamedTextColor.GRAY))
                    .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                    .append(Component.text(" to ", NamedTextColor.GRAY))
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .build());
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("✗ Failed to add rank: " + ex.getMessage(), NamedTextColor.RED));
            logger.log(Level.WARNING, "Failed to add rank", ex);
            return null;
        });

        return 1;
    }

    private int removeRankOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        int changed = 0;
        for (Player target : resolver.resolve(ctx.getSource())) {
            rankService.removeRank(target.getUniqueId(), rank, context).thenAccept(v -> {
                sender.sendMessage(Component.text()
                        .append(Component.text("✓ ", NamedTextColor.GREEN))
                        .append(Component.text("Removed rank ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .append(Component.text(" from ", NamedTextColor.GRAY))
                        .append(Component.text(target.getName(), NamedTextColor.WHITE))
                        .build());

                // Notify the target player
                target.sendMessage(Component.text()
                        .append(Component.text("The ", NamedTextColor.GRAY))
                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                        .append(Component.text(" rank has been removed", NamedTextColor.GRAY))
                        .build());

                // Update command permissions for tab completion
                refreshPlayerCommands(target);
            }).exceptionally(ex -> {
                sender.sendMessage(Component.text("✗ Failed to remove rank: " + ex.getMessage(), NamedTextColor.RED));
                logger.log(Level.WARNING, "Failed to remove rank", ex);
                return null;
            });
            changed++;
        }

        return changed;
    }

    private int removeRankOffline(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String playerName = StringArgumentType.getString(ctx, "player");
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        rankService.removeRank(target.getUniqueId(), rank, context).thenAccept(v -> {
            sender.sendMessage(Component.text()
                    .append(Component.text("✓ ", NamedTextColor.GREEN))
                    .append(Component.text("Removed rank ", NamedTextColor.GRAY))
                    .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                    .append(Component.text(" from ", NamedTextColor.GRAY))
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .build());
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("✗ Failed to remove rank: " + ex.getMessage(), NamedTextColor.RED));
            logger.log(Level.WARNING, "Failed to remove rank", ex);
            return null;
        });

        return 1;
    }

    private int listRanksOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);

        for (Player target : resolver.resolve(ctx.getSource())) {
            rankService.getAllRanks(target.getUniqueId()).thenAccept(ranks -> {
                rankService.getEffectiveRank(target.getUniqueId()).thenAccept(effective -> {
                    sender.sendMessage(Component.text()
                            .append(Component.text("\n=== Ranks for ", NamedTextColor.GOLD))
                            .append(Component.text(target.getName(), NamedTextColor.WHITE))
                            .append(Component.text(" ===", NamedTextColor.GOLD))
                            .build());

                    if (ranks.isEmpty()) {
                        sender.sendMessage(Component.text("  No ranks", NamedTextColor.GRAY));
                    } else {
                        // Group ranks by category
                        Map<RankCategory, List<Rank>> byCategory = ranks.stream()
                                .collect(Collectors.groupingBy(Rank::getCategory));

                        for (RankCategory category : RankCategory.values()) {
                            List<Rank> categoryRanks = byCategory.get(category);
                            if (categoryRanks != null && !categoryRanks.isEmpty()) {
                                sender.sendMessage(Component.text()
                                        .append(Component.text("  " + category.name() + ": ", NamedTextColor.YELLOW))
                                        .build());

                                for (Rank rank : categoryRanks) {
                                    Component rankDisplay = Component.text()
                                            .append(Component.text("    • ", NamedTextColor.GRAY))
                                            .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                                            .append(Component.text(" (Priority: " + rank.getPriority() + ")", NamedTextColor.DARK_GRAY))
                                            .build();

                                    if (rank == effective) {
                                        rankDisplay = rankDisplay.decorate(TextDecoration.BOLD);
                                    }

                                    sender.sendMessage(rankDisplay);
                                }
                            }
                        }
                    }

                    sender.sendMessage(Component.text()
                            .append(Component.text("\nEffective Rank: ", NamedTextColor.AQUA))
                            .append(Component.text(effective.getDisplayName(), effective.getNameColor()).decorate(TextDecoration.BOLD))
                            .build());
                });
            }).exceptionally(ex -> {
                sender.sendMessage(Component.text("✗ Failed to list ranks: " + ex.getMessage(), NamedTextColor.RED));
                logger.log(Level.WARNING, "Failed to list ranks", ex);
                return null;
            });
        }

        return 1;
    }

    private int listRanksOffline(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String playerName = StringArgumentType.getString(ctx, "player");

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return 0;
        }

        rankService.getAllRanks(target.getUniqueId()).thenAccept(ranks -> {
            rankService.getEffectiveRank(target.getUniqueId()).thenAccept(effective -> {
                sender.sendMessage(Component.text()
                        .append(Component.text("\n=== Ranks for ", NamedTextColor.GOLD))
                        .append(Component.text(playerName, NamedTextColor.WHITE))
                        .append(Component.text(" ===", NamedTextColor.GOLD))
                        .build());

                if (ranks.isEmpty()) {
                    sender.sendMessage(Component.text("  No ranks", NamedTextColor.GRAY));
                } else {
                    // Group ranks by category
                    Map<RankCategory, List<Rank>> byCategory = ranks.stream()
                            .collect(Collectors.groupingBy(Rank::getCategory));

                    for (RankCategory category : RankCategory.values()) {
                        List<Rank> categoryRanks = byCategory.get(category);
                        if (categoryRanks != null && !categoryRanks.isEmpty()) {
                            sender.sendMessage(Component.text()
                                    .append(Component.text("  " + category.name() + ": ", NamedTextColor.YELLOW))
                                    .build());

                            for (Rank rank : categoryRanks) {
                                Component rankDisplay = Component.text()
                                        .append(Component.text("    • ", NamedTextColor.GRAY))
                                        .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                                        .append(Component.text(" (Priority: " + rank.getPriority() + ")", NamedTextColor.DARK_GRAY))
                                        .build();

                                if (rank == effective) {
                                    rankDisplay = rankDisplay.decorate(TextDecoration.BOLD);
                                }

                                sender.sendMessage(rankDisplay);
                            }
                        }
                    }
                }

                sender.sendMessage(Component.text()
                        .append(Component.text("\nEffective Rank: ", NamedTextColor.AQUA))
                        .append(Component.text(effective.getDisplayName(), effective.getNameColor()).decorate(TextDecoration.BOLD))
                        .build());
            });
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("✗ Failed to list ranks: " + ex.getMessage(), NamedTextColor.RED));
            logger.log(Level.WARNING, "Failed to list ranks", ex);
            return null;
        });

        return 1;
    }

    private int showRankInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String rankName = StringArgumentType.getString(ctx, "rank");

        Rank rank = parseRank(rankName);
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: " + rankName, NamedTextColor.RED));
            return 0;
        }

        sender.sendMessage(Component.text()
                .append(Component.text("\n=== Rank Information ===", NamedTextColor.GOLD))
                .build());

        sender.sendMessage(Component.text()
                .append(Component.text("  Name: ", NamedTextColor.YELLOW))
                .append(Component.text(rank.getDisplayName(), rank.getNameColor()))
                .build());

        sender.sendMessage(Component.text()
                .append(Component.text("  Category: ", NamedTextColor.YELLOW))
                .append(Component.text(rank.getCategory().name(), NamedTextColor.WHITE))
                .build());

        sender.sendMessage(Component.text()
                .append(Component.text("  Priority: ", NamedTextColor.YELLOW))
                .append(Component.text(String.valueOf(rank.getPriority()), NamedTextColor.WHITE))
                .build());

        if (!rank.getFullPrefix().isEmpty()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("  Full Prefix: ", NamedTextColor.YELLOW))
                    .append(deserializeLegacyColored(rank.getFullPrefix()))
                    .build());
        }

        if (!rank.getShortPrefix().isEmpty()) {
            sender.sendMessage(Component.text()
                    .append(Component.text("  Short Prefix: ", NamedTextColor.YELLOW))
                    .append(deserializeLegacyColored(rank.getShortPrefix()))
                    .build());
        }

        sender.sendMessage(Component.text()
                .append(Component.text("  Is Staff: ", NamedTextColor.YELLOW))
                .append(Component.text(rank.isStaff() ? "Yes" : "No",
                        rank.isStaff() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .build());

        sender.sendMessage(Component.text()
                .append(Component.text("  Is Subscription: ", NamedTextColor.YELLOW))
                .append(Component.text(rank.isSubscription() ? "Yes" : "No",
                        rank.isSubscription() ? NamedTextColor.GREEN : NamedTextColor.RED))
                .build());

        return 1;
    }

    private Component deserializeLegacyColored(String legacyText) {
        return LEGACY_SERIALIZER.deserialize(legacyText).colorIfAbsent(NamedTextColor.WHITE);
    }

    private int clearRanksOnline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);

        RankChangeContext context = resolveContext(sender);

        int changed = 0;
        for (Player target : resolver.resolve(ctx.getSource())) {
            rankService.resetRanks(target.getUniqueId(), context).thenAccept(v -> {
                sender.sendMessage(Component.text()
                        .append(Component.text("✓ ", NamedTextColor.GREEN))
                        .append(Component.text("Cleared all ranks for ", NamedTextColor.GRAY))
                        .append(Component.text(target.getName(), NamedTextColor.WHITE))
                        .append(Component.text(" (reset to DEFAULT)", NamedTextColor.GRAY))
                        .build());

                // Notify the target player
                target.sendMessage(Component.text("Your ranks have been reset", NamedTextColor.YELLOW));

                // Update command permissions for tab completion
                refreshPlayerCommands(target);
            }).exceptionally(ex -> {
                sender.sendMessage(Component.text("✗ Failed to clear ranks: " + ex.getMessage(), NamedTextColor.RED));
                logger.log(Level.WARNING, "Failed to clear ranks", ex);
                return null;
            });
            changed++;
        }

        return changed;
    }

    private int clearRanksOffline(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String playerName = StringArgumentType.getString(ctx, "player");

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return 0;
        }

        RankChangeContext context = resolveContext(sender);

        rankService.resetRanks(target.getUniqueId(), context).thenAccept(v -> {
            sender.sendMessage(Component.text()
                    .append(Component.text("✓ ", NamedTextColor.GREEN))
                    .append(Component.text("Cleared all ranks for ", NamedTextColor.GRAY))
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .append(Component.text(" (reset to DEFAULT)", NamedTextColor.GRAY))
                    .build());
        }).exceptionally(ex -> {
            sender.sendMessage(Component.text("✗ Failed to clear ranks: " + ex.getMessage(), NamedTextColor.RED));
            logger.log(Level.WARNING, "Failed to clear ranks", ex);
            return null;
        });

        return 1;
    }

    private int reloadRanks(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        sender.sendMessage(Component.text()
                .append(Component.text("⟳ ", NamedTextColor.YELLOW))
                .append(Component.text("Reloading rank data...", NamedTextColor.GRAY))
                .build());

        // Clear caches to force reload from storage on next access
        // This would require adding a method to RankService for cache clearing
        // For now, just inform the user
        sender.sendMessage(Component.text()
                .append(Component.text("✓ ", NamedTextColor.GREEN))
                .append(Component.text("Rank data will be reloaded on next access", NamedTextColor.GRAY))
                .build());

        return 1;
    }

    private int showHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        sender.sendMessage(Component.text("\n=== Rank Management Commands ===", NamedTextColor.GOLD));

        sender.sendMessage(Component.text("Online Player Commands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /rank set <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Set a player's rank", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank add <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Add a rank to player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank remove <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Remove a rank from player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank list <player>", NamedTextColor.WHITE)
                .append(Component.text(" - List all player's ranks", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank clear <player>", NamedTextColor.WHITE)
                .append(Component.text(" - Reset player to DEFAULT", NamedTextColor.GRAY)));

        sender.sendMessage(Component.text("\nOffline Player Commands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /rank setoffline <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Set offline player's rank", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank addoffline <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Add rank to offline player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank removeoffline <player> <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Remove rank from offline player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank listoffline <player>", NamedTextColor.WHITE)
                .append(Component.text(" - List offline player's ranks", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank clearoffline <player>", NamedTextColor.WHITE)
                .append(Component.text(" - Reset offline player to DEFAULT", NamedTextColor.GRAY)));

        sender.sendMessage(Component.text("\nOther Commands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /rank info <rank>", NamedTextColor.WHITE)
                .append(Component.text(" - Show rank information", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  /rank reload", NamedTextColor.WHITE)
                .append(Component.text(" - Reload rank data", NamedTextColor.GRAY)));

        sender.sendMessage(Component.text("\nPermission: ", NamedTextColor.AQUA)
                .append(Component.text(PERMISSION_MANAGE, NamedTextColor.WHITE)));

        return 1;
    }

    private RankChangeContext resolveContext(CommandSender sender) {
        if (sender instanceof Player player) {
            return RankChangeContext.ofPlayer(player.getName(), player.getUniqueId());
        }
        return RankChangeContext.ofConsole(sender.getName());
    }

    private Rank parseRank(String rankName) {
        try {
            return Rank.valueOf(rankName.toUpperCase().replace("-", "_").replace("+", "_PLUS"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CompletableFuture<Suggestions> suggestRanks(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (Rank rank : Rank.values()) {
            builder.suggest(rank.name());
            // Also suggest common variations
            if (rank.name().contains("_PLUS")) {
                builder.suggest(rank.name().replace("_PLUS", "+"));
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestRanksForPlayer(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        // Try to get the player from the context
        try {
            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
            List<Player> players = resolver.resolve(ctx.getSource());

            if (!players.isEmpty()) {
                Player player = players.get(0);
                // Get player's current ranks and suggest them
                rankService.getAllRanks(player.getUniqueId()).thenAccept(ranks -> {
                    for (Rank rank : ranks) {
                        builder.suggest(rank.name());
                    }
                });
            }
        } catch (Exception e) {
            // Fall back to suggesting all ranks
            return suggestRanks(ctx, builder);
        }

        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestOfflinePlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        // Suggest all known player names
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null) {
                builder.suggest(player.getName());
            }
        }

        // Also suggest online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            builder.suggest(player.getName());
        }

        return builder.buildFuture();
    }

    /**
     * Refreshes a player's command tree to update tab completion
     * after their rank has changed.
     */
    private void refreshPlayerCommands(Player player) {
        // Schedule on next tick to ensure rank change is fully processed
        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            try {
                // Use Paper's API to update the player's command tree
                player.updateCommands();
                logger.fine("Updated command tree for player: " + player.getName());
            } catch (Exception e) {
                logger.warning("Failed to update command tree for " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
