package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.CommandRegistry;

import java.util.Comparator;
import java.util.Objects;

/**
 * Help command to list all available commands
 */
public record HelpCommand(CommandRegistry registry) implements CommandHandler {

    public HelpCommand {
        Objects.requireNonNull(registry, "registry");
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length > 1) {
            // Show help for specific command
            CommandHandler handler = registry.getCommand(args[1]);
            if (handler != null) {
                System.out.println("\nCommand: " + handler.getName());
                System.out.println("Description: " + handler.getDescription());
                System.out.println("Usage: " + handler.getUsage());
                if (handler.getAliases().length > 0) {
                    System.out.println("Aliases: " + String.join(", ", handler.getAliases()));
                }
                return true;
            } else {
                System.out.println("Unknown command: " + args[1]);
                return false;
            }
        }

        // Show all commands
        System.out.println("\nAvailable Commands:");
        System.out.println("═══════════════════════════════════════════════════════════════");

        registry.getAllCommands().stream()
                .sorted(Comparator.comparing(CommandHandler::getName))
                .forEach(handler -> {
                    String aliases = handler.getAliases().length > 0
                            ? " [" + String.join(", ", handler.getAliases()) + "]"
                            : "";
                    System.out.printf("  %-20s %s%s\n",
                            handler.getName(),
                            handler.getDescription(),
                            aliases);
                });

        System.out.println("\nType 'help <command>' for detailed information about a command");

        return true;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"?", "h"};
    }

    @Override
    public String getDescription() {
        return "Show available commands";
    }

    @Override
    public String getUsage() {
        return "help [command]";
    }
}
