package sh.harold.fulcrum.feature.identity;

import static io.papermc.paper.command.brigadier.Commands.*;
import static io.papermc.paper.command.brigadier.argument.ArgumentTypes.*;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.Message;

public class TestCommand {

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("test")
                .requires(source -> source.getExecutor().isOp())
                .then(argument("target", player())
                        .then(argument("count", IntegerArgumentType.integer(1))
                                .then(argument("color", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            builder.suggest("red");
                                            builder.suggest("green");
                                            builder.suggest("blue");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            // Resolve player selector (handles names and selectors like @p)
                                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                                            Player target = resolver.resolve(ctx.getSource()).getFirst();

                                            int count = ctx.getArgument("count", Integer.class);
                                            String color = ctx.getArgument("color", String.class);

                                            // Send message using your custom message system
                                            Message.success("test.success", target.getName(), count, color).staff().debug()
                                                    .send(ctx.getSource().getSender());

                                            return 1;
                                        }))))
                .executes(ctx -> {
                    Message.info("test.usage").send(ctx.getSource().getSender());
                    return 0;
                })
                .build();
    }
}
