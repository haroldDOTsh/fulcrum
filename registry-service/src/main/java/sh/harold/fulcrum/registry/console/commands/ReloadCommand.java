package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;

/**
 * Command to reload configuration
 */
public class ReloadCommand implements CommandHandler {

    private final RegistryService registryService;

    public ReloadCommand(RegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public boolean execute(String[] args) {
        System.out.println("Reloading configuration...");

        try {
            registryService.reloadConfiguration();
            System.out.println("Configuration reloaded successfully.");
            System.out.println("Note: Some changes may require a restart to take effect.");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to reload configuration: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"refresh"};
    }

    @Override
    public String getDescription() {
        return "Reload configuration without restart";
    }

    @Override
    public String getUsage() {
        return "reload";
    }
}