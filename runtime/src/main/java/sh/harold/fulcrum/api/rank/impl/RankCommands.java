package sh.harold.fulcrum.api.rank.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.rank.RankService;
import sh.harold.fulcrum.api.rank.enums.FunctionalRank;
import sh.harold.fulcrum.api.rank.enums.MonthlyPackageRank;
import sh.harold.fulcrum.api.rank.enums.PackageRank;
import sh.harold.fulcrum.api.rank.events.RankChangeEvent;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Administrative commands for rank management using Paper's Brigadier API.
 * Provides comprehensive rank management functionality including info, set, grant, and remove operations.
 */
public final class RankCommands {

    // Suggestion providers for rank enums
    private static final SuggestionProvider<CommandSourceStack> PACKAGE_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(PackageRank.values())
                .map(Enum::name)
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> FUNCTIONAL_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(FunctionalRank.values())
                .map(Enum::name)
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> MONTHLY_RANK_SUGGESTIONS = (context, builder) -> {
        Arrays.stream(MonthlyPackageRank.values())
                .map(Enum::name)
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
                .then(buildHistoryCommand())
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

    private LiteralArgumentBuilder<CommandSourceStack> buildHistoryCommand() {
        return literal("history")
                .then(argument("player", ArgumentTypes.player())
                        .executes(this::executeHistory));
    }


    private LiteralArgumentBuilder<CommandSourceStack> buildGrantCommand() {
        return literal("grant")
                .requires(source -> source.getSender().hasPermission("fulcrum.rank.admin"))
                .then(literal("package")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(PACKAGE_RANK_SUGGESTIONS)
                                        .executes(this::executeGrantPackage))))
                .then(literal("functional")
                        .then(argument("player", ArgumentTypes.player())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests(FUNCTIONAL_RANK_SUGGESTIONS)
                                        .executes(this::executeGrantFunctional))))
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
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
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
                                    Message.info("rank.info.header", target.getName()).staff().send(sender);
                                    Message.info("rank.info.effective", effectiveRank.getEffectiveDisplayName(), effectiveRank.getEffectiveColorCode()).staff().send(sender);
                                    Message.info("rank.info.functional", functionalRank != null ? functionalRank.getDisplayName() : "None").staff().send(sender);
                                    Message.info("rank.info.package", packageRank.getDisplayName()).staff().send(sender);
                                    Message.info("rank.info.monthly", monthlyRank != null ? monthlyRank.getDisplayName() : "None").staff().send(sender);
                                    Message.info("rank.info.priority", effectiveRank.effectivePriority().name()).staff().send(sender);
                                });
                            });
                        });
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-load", throwable.getMessage()).staff().send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeHistory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async operations to get monthly rank history
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            // Get current active monthly rank
            rankService.getActiveMonthlyRankData(playerId).thenAccept(activeRank -> {
                // Get complete monthly rank history
                rankService.getMonthlyRankHistory(playerId).thenAccept(history -> {
                    // Get expired monthly ranks
                    rankService.getExpiredMonthlyRanks(playerId).thenAccept(expiredRanks -> {
                        // Send results back on main thread
                        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                            Message.info("rank.history.header", target.getName()).staff().send(sender);

                            // Show current active rank
                            if (activeRank != null) {
                                long remainingDays = activeRank.getRemainingDays();
                                Message.info("rank.history.current",
                                        activeRank.rank.getDisplayName(),
                                        remainingDays + " day" + (remainingDays != 1 ? "s" : "")
                                ).staff().send(sender);
                            } else {
                                Message.info("rank.history.no-current").staff().send(sender);
                            }

                            // Show full history (for now just count since we haven't implemented the actual database queries)
                            Message.info("rank.history.total", history.size()).staff().send(sender);
                            Message.info("rank.history.expired", expiredRanks.size()).staff().send(sender);

                            // Note: In a real implementation, we would show detailed history here
                            // This demonstrates the structure for when the database queries are implemented
                            if (history.isEmpty() && expiredRanks.isEmpty()) {
                                Message.info("rank.history.none", target.getName()).staff().send(sender);
                            }
                        });
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-load-history", throwable.getMessage()).staff().send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeGrantPackage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = ctx.getArgument("rank", String.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        PackageRank rank;
        try {
            rank = PackageRank.valueOf(rankName);
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-package-rank", rankName,
                    Arrays.toString(PackageRank.values())).staff().send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setPackageRank(playerId, rank).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.set.package.success", target.getName(), rank.getDisplayName()).staff().send(sender);

                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.PACKAGE_RANK_CHANGED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-set", throwable.getMessage()).staff().send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeGrantFunctional(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        String rankName = ctx.getArgument("rank", String.class);

        List<Player> targets = resolver.resolve(ctx.getSource());
        if (targets.isEmpty()) {
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        FunctionalRank rank;
        try {
            rank = FunctionalRank.valueOf(rankName);
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-functional-rank", rankName,
                    Arrays.toString(FunctionalRank.values())).staff().send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setFunctionalRank(playerId, rank).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.set.functional.success", target.getName(), rank.getDisplayName()).staff().send(sender);

                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.FUNCTIONAL_RANK_SET);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-set", throwable.getMessage()).staff().send(sender);
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
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        MonthlyPackageRank rank;
        try {
            rank = MonthlyPackageRank.valueOf(rankName);
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-monthly-rank", rankName,
                    Arrays.toString(MonthlyPackageRank.values())).staff().send(sender);
            return 0;
        }

        Duration duration;
        try {
            duration = parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            Message.error("rank.error.invalid-duration", durationStr).staff().send(sender);
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
                            formatDuration(duration)).staff().send(sender);

                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.MONTHLY_RANK_GRANTED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-grant", throwable.getMessage()).staff().send(sender);
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
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.setFunctionalRank(playerId, null).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.remove.functional.success", target.getName()).staff().send(sender);

                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.FUNCTIONAL_RANK_REMOVED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-remove", throwable.getMessage()).staff().send(sender);
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
            Message.error("rank.error.player-not-found", "No players found").staff().send(sender);
            return 0;
        }

        Player target = targets.get(0);
        UUID playerId = target.getUniqueId();
        RankService rankService = RankFeature.getRankService();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
            rankService.removeMonthlyRank(playerId).thenRun(() -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.success("rank.remove.monthly.success", target.getName()).staff().send(sender);

                    // Fire rank change event
                    rankService.getEffectiveRank(playerId).thenAccept(effectiveRank -> {
                        RankChangeEvent event = new RankChangeEvent(playerId, null, effectiveRank, RankChangeEvent.RankChangeType.MONTHLY_RANK_REMOVED);
                        Bukkit.getPluginManager().callEvent(event);
                    });
                });
            }).exceptionally(throwable -> {
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("Fulcrum"), () -> {
                    Message.error("rank.error.failed-to-remove", throwable.getMessage()).staff().send(sender);
                });
                return null;
            });
        });

        return 1;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        Message.info("rank.list.header").staff().send(sender);
        Message.info("rank.list.section", "Package Ranks").staff().send(sender);
        for (PackageRank rank : PackageRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).staff().send(sender);
        }

        Message.info("rank.list.section", "Functional Ranks").staff().send(sender);
        for (FunctionalRank rank : FunctionalRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).staff().send(sender);
        }

        Message.info("rank.list.section", "Monthly Ranks").staff().send(sender);
        for (MonthlyPackageRank rank : MonthlyPackageRank.values()) {
            Message.info("rank.list.item", rank.name(), rank.getDisplayName(), rank.getColorCode()).staff().send(sender);
        }

        return 1;
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        try {
            // The rank system core functionality doesn't require reloading
            // Display updates are handled by external formatting systems
            Message.success("rank.reload.success").staff().send(sender);
        } catch (Exception e) {
            Message.error("rank.reload.failed", e.getMessage()).staff().send(sender);
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