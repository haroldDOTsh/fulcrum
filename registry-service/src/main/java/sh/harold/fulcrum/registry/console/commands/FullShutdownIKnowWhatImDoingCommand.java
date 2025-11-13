package sh.harold.fulcrum.registry.console.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;

import java.util.List;
import java.util.Objects;

/**
 * Command that clears Redis and shuts the registry down after ensuring no services are alive.
 */
public record FullShutdownIKnowWhatImDoingCommand(RegistryService registryService) implements CommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FullShutdownIKnowWhatImDoingCommand.class);
    private static final int MAX_PRINTED_ENTRIES = 10;

    public FullShutdownIKnowWhatImDoingCommand {
        Objects.requireNonNull(registryService, "registryService");
    }

    @Override
    public boolean execute(String[] args) {
        RegistryService.AliveServiceSnapshot snapshot = registryService.getAliveServiceSnapshot();

        if (snapshot.hasAliveServices()) {
            System.out.println("Full shutdown aborted: active services detected.");
            printServers(snapshot.servers());
            printProxies(snapshot.proxies());
            System.out.println("Ensure all services are stopped and deregistered before retrying.");
            return false;
        }

        System.out.println("No active services detected. Clearing Redis and shutting down the registry.");
        boolean cleared = registryService.clearAllRedisKeys();
        if (!cleared) {
            System.err.println("Unable to clear Redis keys. Check registry logs for details. Full shutdown aborted.");
            return false;
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(100);
                registryService.shutdown();
                System.exit(0);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Full shutdown thread interrupted", interrupted);
                System.exit(1);
            } catch (Exception e) {
                LOGGER.error("Error during full shutdown", e);
                System.err.println("Error during full shutdown: " + e.getMessage());
                System.exit(1);
            }
        }, "FullShutdown-Thread");
        shutdownThread.setDaemon(false);
        shutdownThread.start();

        return true;
    }

    private void printServers(List<RegisteredServerData> servers) {
        if (servers == null || servers.isEmpty()) {
            return;
        }

        System.out.println("Active servers blocking shutdown:");
        int limit = Math.min(servers.size(), MAX_PRINTED_ENTRIES);
        for (int i = 0; i < limit; i++) {
            RegisteredServerData server = servers.get(i);
            System.out.printf(" - %s (%s) status=%s players=%d/%d%n",
                    server.getServerId(),
                    server.getServerType(),
                    server.getStatus(),
                    server.getPlayerCount(),
                    server.getMaxCapacity());
        }
        if (servers.size() > limit) {
            System.out.printf(" ... and %d more server(s)%n", servers.size() - limit);
        }
    }

    private void printProxies(List<RegisteredProxyData> proxies) {
        if (proxies == null || proxies.isEmpty()) {
            return;
        }

        System.out.println("Active proxies blocking shutdown:");
        int limit = Math.min(proxies.size(), MAX_PRINTED_ENTRIES);
        for (int i = 0; i < limit; i++) {
            RegisteredProxyData proxy = proxies.get(i);
            System.out.printf(" - %s (%s:%d) status=%s%n",
                    proxy.getProxyIdString(),
                    proxy.getAddress(),
                    proxy.getPort(),
                    proxy.getStatus());
        }
        if (proxies.size() > limit) {
            System.out.printf(" ... and %d more proxy instance(s)%n", proxies.size() - limit);
        }
    }

    @Override
    public String getName() {
        return "fullshutdowniknowwhatimdoing";
    }

    @Override
    public String[] getAliases() {
        return new String[0];
    }

    @Override
    public String getDescription() {
        return "Clear Redis state and terminate the registry (only when no services remain).";
    }

    @Override
    public String getUsage() {
        return "fullshutdowniknowwhatimdoing";
    }
}
