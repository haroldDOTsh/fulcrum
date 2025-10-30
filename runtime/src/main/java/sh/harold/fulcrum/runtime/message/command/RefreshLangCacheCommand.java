package sh.harold.fulcrum.runtime.message.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.message.Message;
import sh.harold.fulcrum.message.storage.TranslationCache;

public final class RefreshLangCacheCommand {
    private RefreshLangCacheCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create(@NotNull TranslationCache cache) {
        return LiteralArgumentBuilder
                .<CommandSourceStack>literal("refreshlangcache")
                .requires(source -> RankUtils.isStaff(source.getSender()))
                .executes(ctx -> execute(ctx, cache))
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, TranslationCache cache) {
        Audience audience = ctx.getSource().getSender();
        try {
            cache.clear();
            Message.success("Language cache refreshed.")
                    .builder()
                    .skipTranslation()
                    .send(audience);
            return Command.SINGLE_SUCCESS;
        } catch (Exception ex) {
            Message.error("Failed to refresh language cache: {arg0}", ex.getMessage())
                    .builder()
                    .skipTranslation()
                    .send(audience);
            return 0;
        }
    }
}
