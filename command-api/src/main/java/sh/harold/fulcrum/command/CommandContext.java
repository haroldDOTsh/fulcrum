package sh.harold.fulcrum.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;

public class CommandContext {
    private final CommandSourceStack sourceStack;
    private final Audience audience;

    public CommandContext(CommandSourceStack sourceStack) {
        this.sourceStack = sourceStack;
        this.audience = sourceStack.getSender();
    }

    public CommandSourceStack getSourceStack() {
        return sourceStack;
    }

    public Audience getAudience() {
        return audience;
    }

    /**
     * Retrieve a parsed argument by name and type. Throws if not found or wrong type.
     * This is a stub for now; the runtime will provide the actual implementation.
     */
    public <T> T argument(String name, Class<T> type) {
        throw new UnsupportedOperationException("argument() is only implemented at runtime");
    }
}
