package sh.harold.fulcrum.api.message.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.message.YamlMessageService;

public final class MessageReloadCommand {
    private MessageReloadCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create(@NotNull YamlMessageService service) {
        return LiteralArgumentBuilder
                .<CommandSourceStack>literal("messagereload")
                .requires(source -> source.getSender().hasPermission("fulcrum.message.reload"))
                .executes(ctx -> execute(ctx, service))
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, YamlMessageService service) {
        Audience audience = ctx.getSource().getSender();
        try {
            service.loadTranslations();
            Message.success("internal.message.reload.success").send(audience);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            Message.error("internal.message.reload.failure").send(audience);
            return 0;
        }
    }
}
