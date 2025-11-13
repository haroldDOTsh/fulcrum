package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;

import java.util.Objects;

/**
 * Stop command to gracefully shutdown the registry service
 */
public record StopCommand(RegistryService registryService) implements CommandHandler {

    public StopCommand {
        Objects.requireNonNull(registryService, "registryService");
    }

    @Override
    public boolean execute(String[] args) {
        System.out.println("Initiating graceful shutdown...");

        // Shutdown in a separate thread to allow console to respond
        new Thread(() -> {
            try {
                Thread.sleep(100); // Brief delay to allow message to print
                registryService.shutdown();
                System.exit(0);
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
                System.exit(1);
            }
        }, "Shutdown-Thread").start();

        return true;
    }

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"exit", "quit"};
    }

    @Override
    public String getDescription() {
        return "Gracefully shutdown the registry service";
    }

    @Override
    public String getUsage() {
        return "stop";
    }
}
