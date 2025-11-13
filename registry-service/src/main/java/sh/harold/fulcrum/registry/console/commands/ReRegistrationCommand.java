package sh.harold.fulcrum.registry.console.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.api.messagebus.ChannelConstants;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;

import java.util.Map;
import java.util.Objects;

/**
 * Command to manually request re-registration from all servers and proxies.
 * Useful for recovering state after network issues or debugging.
 */
public record ReRegistrationCommand(RegistryService registryService,
                                    MessageBus messageBus) implements CommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReRegistrationCommand.class);

    public ReRegistrationCommand {
        Objects.requireNonNull(registryService, "registryService");
        Objects.requireNonNull(messageBus, "messageBus");
    }

    @Override
    public boolean execute(String[] args) {
        System.out.println("Requesting re-registration from all servers and proxies...");

        try {
            // Create re-registration request
            Map<String, Object> request = Map.of(
                    "timestamp", System.currentTimeMillis(),
                    "reason", "Manual re-registration request from console",
                    "forceReregistration", true
            );

            // Broadcast re-registration request
            messageBus.broadcast(ChannelConstants.REGISTRY_REREGISTRATION_REQUEST, request);
            System.out.println("Broadcast re-registration request to all nodes");

            System.out.println("\nServers and proxies should re-register within a few seconds.");
            System.out.println("Use 'status' command to monitor registration progress.");

            return true;
        } catch (Exception e) {
            System.err.println("Failed to request re-registration: " + e.getMessage());
            LOGGER.error("Error requesting re-registration", e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "reregister";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"re-register", "reregistration"};
    }

    @Override
    public String getDescription() {
        return "Request re-registration from all servers and proxies";
    }

    @Override
    public String getUsage() {
        return "reregister - Request all nodes to re-register with the Registry";
    }
}
