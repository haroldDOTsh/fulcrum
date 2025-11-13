package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;

import java.util.Objects;

/**
 * Command to toggle debug mode on/off
 */
public record DebugCommand(RegistryService registryService) implements CommandHandler {

    public DebugCommand {
        Objects.requireNonNull(registryService, "registryService");
    }

    @Override
    public boolean execute(String[] args) {
        registryService.toggleDebugMode();
        boolean newState = registryService.isDebugMode();

        String status = newState ? "ENABLED" : "DISABLED";
        String color = newState ? TableFormatter.GREEN : TableFormatter.RED;

        System.out.println("Debug mode: " + TableFormatter.color(status, color));

        if (newState) {
            System.out.println("Verbose logging is now enabled. You will see detailed message processing information.");
        } else {
            System.out.println("Verbose logging is now disabled. Only important messages will be shown.");
        }

        return true;
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"verbose"};
    }

    @Override
    public String getDescription() {
        return "Toggle debug mode on/off";
    }

    @Override
    public String getUsage() {
        return "debug";
    }
}
