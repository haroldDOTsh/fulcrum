package sh.harold.fulcrum.command;

import org.bukkit.command.CommandSender;

public class CommandContext {
    private final CommandSender sender;

    public CommandContext(CommandSender sender) {
        this.sender = sender;
    }

    public CommandSender getSender() {
        return sender;
    }

    /**
     * Retrieve a parsed argument by name and type. Throws if not found or wrong type.
     * This is a stub for now; the runtime will provide the actual implementation.
     */
    public <T> T argument(String name, Class<T> type) {
        throw new UnsupportedOperationException("argument() is only implemented at runtime");
    }
}
