package sh.harold.fulcrum.fundamentals.rank;

import static io.papermc.paper.command.brigadier.Commands.*;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.*;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.events.RankChangeEvent;
import sh.harold.fulcrum.api.rank.RankService;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Administrative commands for rank management using Paper's Brigadier API.
 * Provides comprehensive rank management functionality including info, set, grant, and remove operations.
 */
public final class RankCommands {

    // Suggestion providers for rank enums
    private static final SuggestionProvider<CommandSourceStack> PACKAGE_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(PackageRank.values())
                .map(rank -> rank.name().toLowerCase())
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> FUNCTIONAL_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(FunctionalRank.values())
                .map(rank -> rank.name().toLowerCase())
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> MONTHLY_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(MonthlyPackageRank.values())
                .map(rank -> rank.name().toLowerCase().replace("_plus", "+"))
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> DURATION_SUGGESTIONS = (context, builder) -> {
        builder.suggest("30d");
        builder.suggest("7d");
        builder.suggest("1d");
        builder.suggest("12h");
        builder.suggest("1h");
        builder.suggest("30m");
        return builder.buildFuture();
    };

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("rank")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank"))
                .then(buildInfoCommand())
                .then(buildSetCommand())
                .then(buildGrantCommand())
                .then(buildRemoveCommand())
                .then(buildListCommand())
                .then(buildReloadCommand())
                .executes(ctx -> {
                    Message.info("rank.usage").send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildInfoCommand() {
        return literal("info")
                .then(argument("player", ArgumentTypes.player())
                        .executes(this::executeInfo));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildSetCommand() {
        return literal("set")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank.admin"))
                .then(literal("package")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(PACKAGE_RANK_SUGGESTIONS)
                                        .executes(this::executeSetPackage))))
                .then(literal("functional")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(FUNCTIONAL_RANK_SUGGESTIONS)
                                        .executes(this::executeSetFunctional))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildGrantCommand() {
        return literal("grant")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank.admin"))
                .then(literal("monthly")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(MONTHLY_RANK_SUGGESTIONS)
                                        .then(argument("duration", StringArgumentType.word())
                                                .suggests(DURATION_SUGGESTIONS)
                                                .executes(this::executeGrantMonthly)))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildRemoveCommand() {
        return literal("remove")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank.admin"))
                .then(literal("functional")
                        .then(argument("player", ArgumentTypes.player())
                                .executes(this::executeRemoveFunctional)))
                .then(literal("monthly")
                        .then(argument("player", ArgumentTypes.player())
                                .executes(this::executeRemoveMonthly)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildListCommand() {
        return literal("list")
                .executes(this::executeList);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildReloadCommand() {
        return literal("reload")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank.admin"))
                .executes(this::executeReload);
    }

    // Command execution methods

    private int executeInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        
        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async operations
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            CompletableFuture.allOf(
                rankService.getEffectiveRank(playerId),
                rankService.getFunctionalRank(playerId),
                rankService.getPackageRank(playerId),
                rankService.getMonthlyRank(playerId)
            ).thenAccept(ignored -> {
                rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                    rankService.getFunctionalRank(playerId).thenAccept(functionalRank -> {
                        rankService.getPackageRank(playerId).thenAccept(packageRank -> {
                            rankService.getMonthlyRank(playerId).thenAccept(monthlyRank -> {
                                // Send results back on main thread
                                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                                    Message.info("rank.info.header", target.getName()).send(sender);
                                    Message.info("rank.info.effective", effectiveRank.getEffectiveDisplayName(), effectiveRank.getEffectiveColorCode()).send(sender);
                                    Message.info("rank.info.functional", functionalRank != null ? functionalRank.getDisplayName() : "None").send(sender);
                                    Message.info("rank.info.package", packageRank.getDisplayName()).send(sender);
                                    Message.info("rank.info.monthly", monthlyRank != null ? monthlyRank.getDisplayName() : "None").send(sender);
                                    Message.info("rank.info.priority", effectiveRank.effectivePriority().name()).send(sender);
                                });
                            });
                        });
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-load", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeSetPackage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = ctx.getArgument("rank", String.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        PackageRank rank;
        try {
            rank = PackageRank.valueOf(rankName.toUpperCase());
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-package-rank", rankName, 
                Arrays.toString(PackageRank.values())).send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setPackageRank(playerId, rank).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.set.package.success", target.getName(), rank.getDisplayName()).send(sender);
                    
                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.PACKAGE_RANK_CHANGED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-set", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeSetFunctional(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = ctx.getArgument("rank", String.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        FunctionalRank rank;
        try {
            rank = FunctionalRank.valueOf(rankName.toUpperCase());
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-functional-rank", rankName, 
                Arrays.toString(FunctionalRank.values())).send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setFunctionalRank(playerId, rank).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.set.functional.success", target.getName(), rank.getDisplayName()).send(sender);
                    
                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.FUNCTIONAL_RANK_SET);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-set", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeGrantMonthly(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = ctx.getArgument("rank", String.class);
        String durationStr = ctx.getArgument("duration", String.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        MonthlyPackageRank rank;
        try {
            rank = MonthlyPackageRank.valueOf(rankName.toUpperCase().replace("+", "_PLUS"));
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-monthly-rank", rankName, 
                Arrays.toString(MonthlyPackageRank.values())).send(sender);
            return 0;
        }

        Duration duration;
        try {
            duration = parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-duration", durationStr).send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.grantMonthlyRank(playerId, rank, duration).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.grant.monthly.success", target.getName(), rank.getDisplayName(), 
                        formatDuration(duration)).send(sender);
                    
                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.MONTHLY_RANK_GRANTED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-grant", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeRemoveFunctional(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setFunctionalRank(playerId, null).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.remove.functional.success", target.getName()).send(sender);
                    
                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.FUNCTIONAL_RANK_REMOVED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-remove", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeRemoveMonthly(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.removeMonthlyRank(playerId).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.remove.monthly.success", target.getName()).send(sender);
                    
                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.MONTHLY_RANK_REMOVED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-remove", throwable.getMessage()).send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        Message.info("rank.list.header").send(sender);
        Message.info("rank.list.section", "Package Ranks").send(sender);
        for (PackageRank rank : PackageRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).send(sender);
        }

        Message.info("rank.list.section", "Functional Ranks").send(sender);
        for (FunctionalRank rank : FunctionalRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).send(sender);
        }

        Message.info("rank.list.section", "Monthly Ranks").send(sender);
        for (MonthlyPackageRank rank : MonthlyPackageRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).send(sender);
        }

        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        try {
            // Update all online players' displays
            RankDisplayManager displayManager = RankFeature.getRankDisplayManager();
            displayManager.updateAllPlayerTablists().thenRun(() -> {
                Message.success("rank.reload.success").send(sender);
            }).exceptionally(throwable -> {
                Message.error("rank.reload.failed", throwable.getMessage()).send(sender);
                return null;
            });
        } catch (Exception e) {
            Message.error("rank.reload.failed", e.getMessage()).send(sender);
        }

        return 1;
    }

    // Utility methods

    private Duration parseDuration(String durationStr) {
        // Parse duration strings like "30d", "1w", "24h", "60m"
        if (durationStr.matches("\\d+[dwhmsDWHMS]")) {
            char unit = durationStr.charAt(durationStr.length() - 1);
            int amount = Integer.parseInt(durationStr.substring(0, durationStr.length() - 1));
            
            return switch (Character.toLowerCase(unit)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                case 'w' -> Duration.ofDays(amount * 7);
                default -> throw new IllegalArgumentException("Invalid duration unit: " + unit);
            };
        }
        throw new IllegalArgumentException("Invalid duration format. Use format like: 30d, 1w, 24h, 60m");
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + " day" + (days != 1 ? "s" : "");
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " hour" + (hours != 1 ? "s" : "");
        }
        long minutes = duration.toMinutes();
        return minutes + " minute" + (minutes != 1 ? "s" : "");
    }
}