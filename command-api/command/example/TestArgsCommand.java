package sh.harold.fulcrum.command.example;

import net.kyori.adventure.audience.Audience;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.command.CommandContext;
import sh.harold.fulcrum.command.CommandExecutor;
import sh.harold.fulcrum.command.CommandExecutorType;
import sh.harold.fulcrum.command.annotations.*;


/**
 * Example command to test argument parsing.
 */
@Command("testargs")
@Aliases({"targs", "args"})
@Cooldown(seconds = 5)
@Executor(CommandExecutorType.PLAYER)
public final class TestArgsCommand implements CommandExecutor {
    @Argument("stringArg")
    @Suggestions("stringSuggestions")
    public String stringArg;

    @Argument("intArg")
    public int intArg;

    @Override
    public void execute(CommandContext ctx) {
        Audience audience = ctx.getAudience();
        // Use Message API for all runtime messages
        Message.info("args.string", stringArg).staff().send(audience);
        Message.info("args.int", intArg).debug().send(audience);
    }
}
