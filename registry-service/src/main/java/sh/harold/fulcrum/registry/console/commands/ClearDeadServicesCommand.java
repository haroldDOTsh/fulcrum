package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Console command that prunes proxies/servers that have been DEAD longer than a threshold.
 */
public class ClearDeadServicesCommand implements CommandHandler {

    private static final long DEFAULT_THRESHOLD_MINUTES = 5;

    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;

    public ClearDeadServicesCommand(ServerRegistry serverRegistry,
                                    ProxyRegistry proxyRegistry,
                                    HeartbeatMonitor heartbeatMonitor) {
        this.serverRegistry = serverRegistry;
        this.proxyRegistry = proxyRegistry;
        this.heartbeatMonitor = heartbeatMonitor;
    }

    @Override
    public boolean execute(String[] args) {
        long thresholdMinutes = DEFAULT_THRESHOLD_MINUTES;
        String scope = "all";

        if (args.length > 1) {
            try {
                thresholdMinutes = Long.parseLong(args[1]);
                if (thresholdMinutes <= 0) {
                    System.out.println("Threshold must be a positive number of minutes.");
                    return false;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid minute threshold: " + args[1]);
                return false;
            }
        }

        if (args.length > 2) {
            scope = args[2].toLowerCase(Locale.ROOT);
            if (!scope.equals("all") && !scope.equals("proxies") && !scope.equals("servers")) {
                System.out.println("Invalid scope '" + args[2] + "'. Use all|proxies|servers.");
                return false;
            }
        }

        long cutoffMillis = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(thresholdMinutes);
        boolean purgeProxies = !"servers".equals(scope);
        boolean purgeServers = !"proxies".equals(scope);

        int removedProxies = purgeProxies ? clearDeadProxies(cutoffMillis) : 0;
        int removedServers = purgeServers ? clearDeadServers(cutoffMillis) : 0;

        if (removedProxies == 0 && removedServers == 0) {
            System.out.println("No dead services exceeded the " + thresholdMinutes + " minute threshold.");
        } else {
            if (removedProxies > 0) {
                System.out.printf("Removed %d proxy %s older than %d minute(s).%n",
                        removedProxies, removedProxies == 1 ? "entry" : "entries", thresholdMinutes);
            }
            if (removedServers > 0) {
                System.out.printf("Removed %d server %s older than %d minute(s).%n",
                        removedServers, removedServers == 1 ? "entry" : "entries", thresholdMinutes);
            }
        }
        return true;
    }

    private int clearDeadProxies(long cutoffMillis) {
        if (proxyRegistry == null) {
            return 0;
        }

        List<RegisteredProxyData> candidates = new ArrayList<>();
        for (RegisteredProxyData proxy : proxyRegistry.getAllProxies()) {
            if (proxy == null || proxy.getStatus() != RegisteredProxyData.Status.DEAD) {
                continue;
            }
            long lastHeartbeat = proxy.getLastHeartbeat();
            if (lastHeartbeat == 0 || lastHeartbeat >= cutoffMillis) {
                continue;
            }
            candidates.add(proxy);
        }

        if (candidates.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (RegisteredProxyData proxy : candidates) {
            String proxyId = proxy.getProxyIdString();
            if (heartbeatMonitor != null) {
                heartbeatMonitor.unregisterProxy(proxyId);
            }
            if (proxyRegistry.removeProxyImmediately(proxyId)) {
                removed++;
                long minutesAgo = Math.max(1,
                        (System.currentTimeMillis() - proxy.getLastHeartbeat()) / TimeUnit.MINUTES.toMillis(1));
                System.out.printf("  - Removed proxy %s (last heartbeat %d minute(s) ago)%n",
                        proxyId, minutesAgo);
            }
        }
        return removed;
    }

    private int clearDeadServers(long cutoffMillis) {
        if (serverRegistry == null) {
            return 0;
        }

        List<RegisteredServerData> candidates = new ArrayList<>();
        for (RegisteredServerData server : serverRegistry.getAllServers()) {
            if (server == null || server.getStatus() != RegisteredServerData.Status.DEAD) {
                continue;
            }
            long lastHeartbeat = server.getLastHeartbeat();
            if (lastHeartbeat == 0 || lastHeartbeat >= cutoffMillis) {
                continue;
            }
            candidates.add(server);
        }

        if (candidates.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (RegisteredServerData server : candidates) {
            serverRegistry.deregisterServer(server.getServerId());
            removed++;
            long minutesAgo = Math.max(1,
                    (System.currentTimeMillis() - server.getLastHeartbeat()) / TimeUnit.MINUTES.toMillis(1));
            System.out.printf("  - Removed backend server %s (last heartbeat %d minute(s) ago)%n",
                    server.getServerId(), minutesAgo);
        }
        return removed;
    }

    @Override
    public String getName() {
        return "cleardead";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"purgedead"};
    }

    @Override
    public String getDescription() {
        return "Remove proxies/servers stuck in DEAD state beyond a threshold.";
    }

    @Override
    public String getUsage() {
        return "cleardead [minutes] [all|proxies|servers]";
    }
}
