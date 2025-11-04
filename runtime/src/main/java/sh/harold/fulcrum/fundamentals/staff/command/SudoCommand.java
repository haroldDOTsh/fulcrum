package sh.harold.fulcrum.fundamentals.staff.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.EntitySelectorArgumentResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.entities;

/**
 * Brigadier command builder for /sudo.
 */
public final class SudoCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("sudo")
                .requires(source -> RankUtils.hasRankOrHigher(source.getSender(), Rank.STAFF))
                .then(argument("targets", entities())
                        .then(argument("action", greedyString())
                                .executes(this::execute)))
                .executes(ctx -> {
                    Message.error("Usage: /sudo <selector> <command|c:message>")
                            .builder()
                            .staff()
                            .skipTranslation()
                            .send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }

    private int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        CommandSender sender = source.getSender();
        EntitySelectorArgumentResolver resolver = context.getArgument("targets", EntitySelectorArgumentResolver.class);

        List<Player> targets = resolver.resolve(source).stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();

        if (targets.isEmpty()) {
            Message.error("No matching players found.")
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
            return 0;
        }

        SudoAction action = parseAction(context.getArgument("action", String.class));
        if (action.payload().isBlank()) {
            Message.error("Provide a command or use c:<message>.")
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
            return 0;
        }

        if (action.mode() == ActionMode.CHAT) {
            forceChat(targets, action.payload());
            Message.success("Forced {arg0} player(s) to chat: {arg1}", String.valueOf(targets.size()), action.payload())
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
        } else {
            int executed = forceCommand(targets, action.payload());
            if (executed == 0) {
                Message.error("Command failed for every target (unknown command?).")
                        .builder()
                        .staff()
                        .skipTranslation()
                        .send(sender);
                return 0;
            }
            Message.success("Executed \"/{arg0}\" for {arg1} player(s).", action.payload(), String.valueOf(executed))
                    .builder()
                    .staff()
                    .skipTranslation()
                    .send(sender);
        }

        return Command.SINGLE_SUCCESS;
    }

    private void forceChat(List<Player> targets, String message) {
        for (Player target : targets) {
            target.chat(message);
        }
    }

    private int forceCommand(List<Player> targets, String command) {
        int success = 0;
        for (Player target : targets) {
            if (target.performCommand(command)) {
                success++;
            }
        }
        return success;
    }

    private SudoAction parseAction(String rawInput) {
        if (rawInput == null) {
            return new SudoAction(ActionMode.COMMAND, "");
        }
        String trimmed = rawInput.strip();
        if (trimmed.regionMatches(true, 0, "c:", 0, 2)) {
            String chatMessage = trimmed.substring(2).stripLeading();
            return new SudoAction(ActionMode.CHAT, chatMessage);
        }
        return new SudoAction(ActionMode.COMMAND, trimmed);
    }

    private enum ActionMode {
        COMMAND,
        CHAT
    }

    private record SudoAction(ActionMode mode, String payload) {
    }
}
