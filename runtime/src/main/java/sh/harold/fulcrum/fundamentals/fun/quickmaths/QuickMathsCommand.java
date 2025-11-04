package sh.harold.fulcrum.fundamentals.fun.quickmaths;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import sh.harold.fulcrum.api.rank.Rank;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.fundamentals.fun.quickmaths.QuickMathsManager.Difficulty;
import sh.harold.fulcrum.message.Message;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class QuickMathsCommand {
    private static final SuggestionProvider<CommandSourceStack> DIFFICULTY_SUGGESTIONS =
            (context, builder) -> {
                for (Difficulty value : Difficulty.values()) {
                    builder.suggest(value.name());
                }
                return builder.buildFuture();
            };

    private final QuickMathsManager manager;

    public QuickMathsCommand(QuickMathsManager manager) {
        this.manager = manager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("quickmaths")
                .requires(source -> RankUtils.hasRankOrHigher(source.getSender(), Rank.STAFF))
                .then(literal("cancel")
                        .executes(this::executeCancel))
                .then(argument("difficulty", StringArgumentType.word())
                        .suggests(DIFFICULTY_SUGGESTIONS)
                        .then(argument("winners", IntegerArgumentType.integer(1, manager.maxWinnersPerRound()))
                                .executes(this::execute)))
                .build();
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String difficultyRaw = StringArgumentType.getString(context, "difficulty");
        Optional<Difficulty> difficulty = Difficulty.parse(difficultyRaw);
        if (difficulty.isEmpty()) {
            Message.error("Unknown difficulty '" + difficultyRaw + "'. Use " + readableDifficulties() + ".")
                    .builder()
                    .skipTranslation()
                    .send(sender);
            return 0;
        }

        int winners = IntegerArgumentType.getInteger(context, "winners");
        boolean started = manager.startRound(sender, difficulty.get(), winners);
        return started ? Command.SINGLE_SUCCESS : 0;
    }

    private int executeCancel(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        boolean cancelled = manager.cancelRound(sender);
        return cancelled ? Command.SINGLE_SUCCESS : 0;
    }

    private String readableDifficulties() {
        return Arrays.stream(Difficulty.values())
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
