package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;

/**
 * Stop command to gracefully shutdown the registry service
 */
public class StopCommand implements CommandHandler {

    private final RegistryService registryService;

    public StopCommand(RegistryService registryService) {
        this.registryService = registryService;
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
