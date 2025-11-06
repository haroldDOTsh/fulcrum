package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Command to list registered proxies with pagination
 */
public class ProxyRegistryCommand implements CommandHandler {

    private static final int ITEMS_PER_PAGE = 10;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final RedisRegistryInspector inspector;

    public ProxyRegistryCommand(RedisRegistryInspector inspector) {
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

        List<RedisRegistryInspector.ProxyView> proxyViews =
                new ArrayList<>(inspector.fetchProxies());
        if (proxyViews.isEmpty()) {
            System.out.println("No proxies registered.");
            return true;
        }

        // Sort proxies: active first, then by proxy ID
        proxyViews.sort((a, b) -> {
            boolean aDead = a.recentlyDead() || a.status() == RegisteredProxyData.Status.DEAD;
            boolean bDead = b.recentlyDead() || b.status() == RegisteredProxyData.Status.DEAD;
            if (aDead && !bDead) {
                return 1;
            } else if (!aDead && bDead) {
                return -1;
            }
            return a.proxyId().compareTo(b.proxyId());
        });

        // Calculate pagination
        int totalPages = (proxyViews.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (page > totalPages) {
            System.out.println("Page " + page + " does not exist. Total pages: " + totalPages);
            return false;
        }

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, proxyViews.size());

        // Create table
        TableFormatter table = new TableFormatter();
        table.addHeaders("Proxy ID", "Address", "Port", "Last Heartbeat", "Status");

        long currentTime = System.currentTimeMillis();
        for (int i = startIndex; i < endIndex; i++) {
            RedisRegistryInspector.ProxyView proxy = proxyViews.get(i);

            // Use the proxy's actual status if available
            String status;
            String statusColored;

            if (proxy.status() != null) {
                status = proxy.status().toString();
                switch (proxy.status()) {
                    case AVAILABLE:
                        statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_GREEN);
                        break;
                    case EVACUATING:
                        statusColored = TableFormatter.color(status, TableFormatter.YELLOW);
                        break;
                    case UNAVAILABLE:
                        statusColored = TableFormatter.color(status, TableFormatter.YELLOW);
                        break;
                    case DEAD:
                        statusColored = TableFormatter.color("STALLED", TableFormatter.BRIGHT_RED);
                        break;
                    default:
                        statusColored = status;
                }
            } else {
                // Fallback: Calculate based on heartbeat (15 seconds timeout)
                long timeSinceHeartbeat = currentTime - proxy.lastHeartbeat();
                if (timeSinceHeartbeat < 15000) {
                    status = "ONLINE";
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_GREEN);
                } else {
                    status = "OFFLINE";
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_RED);
                }
            }

            String heartbeatTime = DATE_FORMAT.format(new Date(proxy.lastHeartbeat()));

            table.addRow(
                    proxy.proxyId(),
                    proxy.address(),
                    String.valueOf(proxy.port()),
                    heartbeatTime,
                    statusColored
            );
        }

        System.out.println("\nRegistered Proxies (Page " + page + " of " + totalPages + "):");
        System.out.println(table.build());

        if (totalPages > 1) {
            System.out.println("\nUse 'proxyregistry <page>' to view other pages");
        }

        // Show statistics
        int activeCount = (int) proxyViews.stream()
                .filter(p -> p.status() != RegisteredProxyData.Status.DEAD && !p.recentlyDead())
                .count();
        int deadCount = (int) proxyViews.stream()
                .filter(p -> p.status() == RegisteredProxyData.Status.DEAD || p.recentlyDead())
                .count();

        System.out.println("\nProxy Statistics:");
        System.out.println("  Total proxies: " + proxyViews.size());
        System.out.println("  Active: " + activeCount);
        if (deadCount > 0) {
            System.out.println("  Dead/Stalled: " + deadCount);
            System.out.println("\n  Note: Dead/stalled proxies are shown for 60 seconds after failure");
        }

        return true;
    }

    @Override
    public String getName() {
        return "proxyregistry";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"proxies", "pr"};
    }

    @Override
    public String getDescription() {
        return "List registered proxies";
    }

    @Override
    public String getUsage() {
        return "proxyregistry [page]";
    }
}
