package sh.harold.fulcrum.command;

import org.bukkit.command.CommandSender;

public final class CommandContext {
    private final CommandSender sender;

    public CommandContext(CommandSender sender) {
        this.sender = sender;
    }

    public CommandSender getSender() {
        return sender;
    }
}
