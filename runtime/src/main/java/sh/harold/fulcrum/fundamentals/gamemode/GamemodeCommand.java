package sh.harold.fulcrum.fundamentals.gamemode;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.gameMode;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.player;

public final class GamemodeCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("gm")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
                .then(argument("gamemode", gameMode())
                        .executes(ctx -> {
                            return changeGamemodeSelf(ctx.getSource(), ctx.getArgument("gamemode", GameMode.class));
                        })
                        .then(argument("target", player())
                                .executes(ctx -> {
                                    GameMode mode = ctx.getArgument("gamemode", GameMode.class);
                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                                    return changeGamemodeOthers(ctx.getSource(), resolver, mode);
                                })))
                .executes(ctx -> {
                    Message.info("gamemode.usage").send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }

    private int changeGamemodeSelf(CommandSourceStack source, GameMode mode) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            Message.info("gamemode.only-player").send(sender);
            return 0;
        }

        player.setGameMode(mode);
        Message.info("gamemode.changed.self", mode.name()).send(player);
        return 1;
    }

    private int changeGamemodeOthers(CommandSourceStack source, PlayerSelectorArgumentResolver resolver, GameMode mode) throws CommandSyntaxException {
        int changed = 0;

        for (Player target : resolver.resolve(source)) {
            target.setGameMode(mode);
            Message.info("gamemode.changed.target", mode.name(), target.getName()).send(target);
            changed++;
        }

        Message.info("gamemode.changed.count", mode.name(), changed).send(source.getSender());
        return changed;
    }
}
