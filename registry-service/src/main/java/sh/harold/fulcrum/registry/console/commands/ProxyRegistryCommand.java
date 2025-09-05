package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;
import sh.harold.fulcrum.registry.heartbeat.HeartbeatMonitor;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
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
    
    private final ProxyRegistry proxyRegistry;
    private final HeartbeatMonitor heartbeatMonitor;
    
    public ProxyRegistryCommand(ProxyRegistry proxyRegistry) {
        this(proxyRegistry, null);
    }
    
    public ProxyRegistryCommand(ProxyRegistry proxyRegistry, HeartbeatMonitor heartbeatMonitor) {
        this.proxyRegistry = proxyRegistry;
        this.heartbeatMonitor = heartbeatMonitor;
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
        
        // Get all active proxies
        List<RegisteredProxyData> proxies = new ArrayList<>(proxyRegistry.getAllProxies());
        
        // Add recently dead proxies if heartbeat monitor is available
        if (heartbeatMonitor != null) {
            proxies.addAll(heartbeatMonitor.getRecentlyDeadProxies());
        }
        
        // Sort proxies: active first, then by proxy ID
        proxies.sort((a, b) -> {
            boolean aDead = a.getStatus() == RegisteredProxyData.Status.DEAD;
            boolean bDead = b.getStatus() == RegisteredProxyData.Status.DEAD;
            if (aDead && !bDead) {
                return 1;
            } else if (!aDead && bDead) {
                return -1;
            }
            return a.getProxyId().compareTo(b.getProxyId());
        });
        
        if (proxies.isEmpty()) {
            System.out.println("No proxies registered.");
            return true;
        }
        
        // Calculate pagination
        int totalPages = (proxies.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (page > totalPages) {
            System.out.println("Page " + page + " does not exist. Total pages: " + totalPages);
            return false;
        }
        
        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, proxies.size());
        
        // Create table
        TableFormatter table = new TableFormatter();
        table.addHeaders("Proxy ID", "Address", "Port", "Last Heartbeat", "Status");
        
        long currentTime = System.currentTimeMillis();
        for (int i = startIndex; i < endIndex; i++) {
            RegisteredProxyData proxy = proxies.get(i);
            
            // Use the proxy's actual status if available
            String status;
            String statusColored;
            
            if (proxy.getStatus() != null) {
                status = proxy.getStatus().toString();
                switch (proxy.getStatus()) {
                    case AVAILABLE:
                        statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_GREEN);
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
                long timeSinceHeartbeat = currentTime - proxy.getLastHeartbeat();
                if (timeSinceHeartbeat < 15000) {
                    status = "ONLINE";
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_GREEN);
                } else {
                    status = "OFFLINE";
                    statusColored = TableFormatter.color(status, TableFormatter.BRIGHT_RED);
                }
            }
            
            String heartbeatTime = DATE_FORMAT.format(new Date(proxy.getLastHeartbeat()));
            
            table.addRow(
                proxy.getProxyIdString(),
                proxy.getAddress(),
                String.valueOf(proxy.getPort()),
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
        int activeCount = (int) proxies.stream()
            .filter(p -> p.getStatus() != RegisteredProxyData.Status.DEAD)
            .count();
        int deadCount = proxies.size() - activeCount;
        
        System.out.println("\nProxy Statistics:");
        System.out.println("  Total proxies: " + proxies.size());
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
        return new String[] {"proxies", "pr"};
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