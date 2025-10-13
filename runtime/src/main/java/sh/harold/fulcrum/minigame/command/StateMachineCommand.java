package sh.harold.fulcrum.minigame.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.minigame.MinigameEngine;
import sh.harold.fulcrum.minigame.match.MinigameMatch;
import sh.harold.fulcrum.minigame.state.machine.StateMachine;

import java.util.Optional;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;

public final class StateMachineCommand {
    private final MinigameEngine engine;

    public StateMachineCommand(MinigameEngine engine) {
        this.engine = engine;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("statemachine")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
                .then(Commands.literal("query")
                        .executes(ctx -> executeQuery(ctx, ctx.getSource())))
                .then(Commands.literal("freeze")
                        .executes(ctx -> executeFreeze(ctx, ctx.getSource())))
                .then(Commands.literal("resume")
                        .executes(ctx -> executeResume(ctx, ctx.getSource())))
                .then(Commands.literal("skip")
                        .then(Commands.argument("state", string())
                                .executes(ctx -> executeSkip(ctx, ctx.getSource(), getString(ctx, "state")))))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text("Usage: /statemachine <query|freeze|resume|skip> [state]", NamedTextColor.YELLOW));
                    return 0;
                })
                .build();
    }

    private int executeQuery(CommandContext<CommandSourceStack> ctx, CommandSourceStack source) {
        return locateDebug(ctx).map(debug -> {
            debug.describe(source.getSender());
            return 1;
        }).orElse(0);
    }

    private int executeFreeze(CommandContext<CommandSourceStack> ctx, CommandSourceStack source) {
        return locateDebug(ctx).map(debug -> {
            debug.freeze(source.getSender());
            return 1;
        }).orElse(0);
    }

    private int executeResume(CommandContext<CommandSourceStack> ctx, CommandSourceStack source) {
        return locateDebug(ctx).map(debug -> {
            debug.resume(source.getSender());
            return 1;
        }).orElse(0);
    }

    private int executeSkip(CommandContext<CommandSourceStack> ctx, CommandSourceStack source, String targetState) {
        return locateDebug(ctx).map(debug -> {
            debug.requestTransition(source.getSender(), targetState);
            return 1;
        }).orElse(0);
    }

    private Optional<MinigameStateDebug> locateDebug(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Component.text("Only players can use /statemachine.", NamedTextColor.RED));
            return Optional.empty();
        }
        return engine.findMatchByPlayer(player.getUniqueId())
                .map(MinigameStateDebug::new)
                .or(() -> {
                    ctx.getSource().getSender().sendMessage(Component.text("You are not currently in a minigame.", NamedTextColor.RED));
                    return Optional.empty();
                });
    }

    private record MinigameStateDebug(MinigameMatch match) {

        private StateMachine machine() {
                return match.getContext().getStateMachine();
            }

            void describe(CommandSender sender) {
                StateMachine machine = machine();
                var context = match.getContext();
                sender.sendMessage(Component.text("=== State Machine ===", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Match: " + match.getMatchId(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Current: " + machine.getCurrentStateId(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("Frozen: " + context.isStateMachineFrozen(), NamedTextColor.GRAY));
                context.getQueuedTransition()
                        .ifPresent(target -> sender.sendMessage(Component.text("Queued Transition: " + target, NamedTextColor.YELLOW)));
            }

            void freeze(CommandSender sender) {
                match.getContext().freezeStateMachine(sender);
            }

            void resume(CommandSender sender) {
                match.getContext().resumeStateMachine(sender);
            }

            void requestTransition(CommandSender sender, String stateId) {
                match.getContext().requestTransition(stateId);
                sender.sendMessage(Component.text("Queued transition to " + stateId + ".", NamedTextColor.YELLOW));
            }
        }
}
