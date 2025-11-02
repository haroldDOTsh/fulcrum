package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector;
import sh.harold.fulcrum.registry.server.RegisteredServerData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Command to list registered backend servers with pagination
 */
public class BackendRegistryCommand implements CommandHandler {

    private static final int ITEMS_PER_PAGE = 10;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final RedisRegistryInspector inspector;

    public BackendRegistryCommand(RedisRegistryInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public boolean execute(String[] args) {
        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                System.out.println("Invalid page number: " + args[1]);
                return false;
            }
        }

        List<RedisRegistryInspector.ServerView> serverViews =
                new ArrayList<>(inspector.fetchServers());

        if (serverViews.isEmpty()) {
            System.out.println("No backend servers registered.");
            return true;
        }

        // Sort servers: active first, then by server ID
        serverViews.sort((a, b) -> {
            boolean aDead = a.recentlyDead() || a.status() == RegisteredServerData.Status.DEAD;
            boolean bDead = b.recentlyDead() || b.status() == RegisteredServerData.Status.DEAD;
            if (aDead && !bDead) {
                return 1;
            } else if (!aDead && bDead) {
                return -1;
            }
            return a.serverId().compareTo(b.serverId());
        });

        List<RegisteredServerData> servers = serverViews.stream()
                .map(RedisRegistryInspector.ServerView::snapshot)
                .toList();

        // Calculate pagination
        int totalPages = (servers.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (page > totalPages) {
            System.out.println("Page " + page + " does not exist. Total pages: " + totalPages);
            return false;
        }

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, servers.size());

        // Create table
        TableFormatter table = new TableFormatter();
        table.addHeaders("Server ID", "Type", "Role", "Address:Port", "Players", "TPS", "Last Heartbeat", "Status");

        long currentTime = System.currentTimeMillis();
        for (int i = startIndex; i < endIndex; i++) {
            RegisteredServerData server = servers.get(i);

            // Use the server's actual status
            RegisteredServerData.Status serverStatus = server.getStatus();
            String status = serverStatus.toString();
            String statusColored;

            switch (serverStatus) {
                case AVAILABLE:
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_GREEN);
                    break;
                case UNAVAILABLE:
                    statusColored = TableFormatter.color(status, TableFormatter.YELLOW);
                    break;
                case DEAD:
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_RED);
                    break;
                default:
                    statusColored = status;
            }

            String heartbeatTime = DATE_FORMAT.format(new Date(server.getLastHeartbeat()));
            String addressPort = server.getAddress() + ":" + server.getPort();
            String playerInfo = server.getPlayerCount() + "/" + server.getMaxCapacity();

            // Color TPS based on performance
            String tpsString = String.format("%.1f", server.getTps());
            String tpsColored;
            if (server.getTps() >= 19.0) {
                tpsColored = TableFormatter.color(tpsString, TableFormatter.GREEN);
            } else if (server.getTps() >= 15.0) {
                tpsColored = TableFormatter.color(tpsString, TableFormatter.YELLOW);
            } else {
                tpsColored = TableFormatter.color(tpsString, TableFormatter.RED);
            }

            table.addRow(
                    server.getServerId(),
                    server.getServerType(),
                    server.getRole() != null ? server.getRole() : "N/A",
                    addressPort,
                    playerInfo,
                    tpsColored,
                    heartbeatTime,
                    statusColored
            );
        }

        System.out.println("\nRegistered Backend Servers (Page " + page + " of " + totalPages + "):");
        System.out.println(table.build());

        if (totalPages > 1) {
            System.out.println("\nUse 'backendregistry <page>' to view other pages");
        }

        // Show server statistics
        System.out.println("\nServer Statistics:");
        System.out.println("  Total servers: " + servers.size());
        long availableCount = servers.stream()
                .filter(server -> server.getStatus() == RegisteredServerData.Status.AVAILABLE)
                .count();
        long unavailableCount = servers.stream()
                .filter(server -> server.getStatus() == RegisteredServerData.Status.UNAVAILABLE)
                .count();
        long recentlyDeadCount = serverViews.stream()
                .filter(view -> view.recentlyDead() || view.status() == RegisteredServerData.Status.DEAD)
                .count();

        System.out.println("  Available: " + availableCount);
        System.out.println("  Unavailable: " + unavailableCount);
        System.out.println("  Dead/Stalled: " + recentlyDeadCount);

        if (recentlyDeadCount > 0) {
            System.out.println("\n  Note: Dead/stalled servers are shown for 60 seconds after failure");
        }

        return true;
    }

    @Override
    public String getName() {
        return "backendregistry";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"backends", "servers", "br"};
    }

    @Override
    public String getDescription() {
        return "List registered backend servers";
    }

    @Override
    public String getUsage() {
        return "backendregistry [page]";
    }
}
