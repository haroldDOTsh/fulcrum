package sh.harold.fulcrum.fundamentals.staff.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;

import java.util.Objects;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Brigadier command builder for /loop.
 */
public final class LoopCommand {
    private final JavaPlugin plugin;

    public LoopCommand(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("loop")
                .requires(source -> RankUtils.hasRankOrHigher(source.getSender(), Rank.STAFF))
                .then(argument("iterations", integer(1))
                        .then(argument("delay", integer(1))
                                .then(argument("command", greedyString())
                                        .executes(this::execute))))
                .executes(ctx -> {
                    Message.error("Usage: /loop <iterations> <delayTicks> <command>")
                            .builder()
                            .staff()
                            .skipTranslation()
                            .send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        int iterations = context.getArgument("iterations", Integer.class);
        int delay = context.getArgument("delay", Integer.class);
        String rawCommand = context.getArgument("command", String.class);
        String command = sanitizeCommand(rawCommand);

        if (command.isBlank()) {
            Message.error("Provide a valid command to loop (without the leading /).")
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
            return 0;
        }

        scheduleLoop(sender, iterations, delay, command);
        Message.info("Looping \"/{arg0}\" {arg1}x with {arg2} tick delay.", command, String.valueOf(iterations), String.valueOf(delay))
                .builder()
                .staff()
                .skipTranslation()
                .send(sender);
        return Command.SINGLE_SUCCESS;
    }

    private void scheduleLoop(CommandSender sender, int iterations, int delay, String command) {
        new BukkitRunnable() {
            private int executed = 0;

            @Override
            public void run() {
                if (!plugin.isEnabled() || executed >= iterations) {
                    cancel();
                    return;
                }
                boolean success = plugin.getServer().dispatchCommand(sender, command);
                executed++;
                if (!success) {
                    Message.error("Loop cancelled; \"/{arg0}\" failed on iteration {arg1}.", command, String.valueOf(executed))
                            .builder()
                            .staff()
                            .skipTranslation()
                            .send(sender);
                    cancel();
                    return;
                }
                if (executed >= iterations) {
                    Message.success("Loop finished: ran \"/{arg0}\" {arg1} times.", command, String.valueOf(executed))
                            .builder()
                            .staff()
                            .skipTranslation()
                            .send(sender);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, delay);
    }

    private String sanitizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("/")) {
            return trimmed.substring(1).stripLeading();
        }
        return trimmed;
    }
}
