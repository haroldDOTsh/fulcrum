package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;

import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Command to show overall system status
 */
public class StatusCommand implements CommandHandler {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final RegistryService registryService;
    private final long startTime;

    public StatusCommand(RegistryService registryService) {
        this.registryService = registryService;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public boolean execute(String[] args) {
        System.out.println("\n" + TableFormatter.color("═══ Registry Service Status ═══", TableFormatter.CYAN));
        System.out.println();

        // Uptime
        long uptime = System.currentTimeMillis() - startTime;
        String uptimeStr = formatUptime(uptime);
        System.out.println("Uptime: " + TableFormatter.color(uptimeStr, TableFormatter.GREEN));
        System.out.println("Started: " + DATE_FORMAT.format(new Date(startTime)));
        System.out.println();

        // Debug mode status
        boolean debugMode = registryService.isDebugMode();
        System.out.println("Debug Mode: " +
                (debugMode ? TableFormatter.color("ENABLED", TableFormatter.YELLOW)
                        : "DISABLED"));
        System.out.println();

        // Proxy statistics
        int totalProxies = registryService.getProxyRegistry().getProxyCount();
        System.out.println(TableFormatter.color("Proxy Statistics:", TableFormatter.YELLOW));
        System.out.println("  Total Registered: " + totalProxies);
        System.out.println();

        // Server statistics
        int totalServers = registryService.getServerRegistry().getServerCount();
        System.out.println(TableFormatter.color("Server Statistics:", TableFormatter.YELLOW));
        System.out.println("  Total Registered: " + totalServers);

        // Count by type
        int miniCount = registryService.getServerRegistry().getServersByType("mini").size();
        int megaCount = registryService.getServerRegistry().getServersByType("mega").size();
        int poolCount = registryService.getServerRegistry().getServersByType("pool").size();

        if (miniCount > 0 || megaCount > 0 || poolCount > 0) {
            System.out.println("  By Type:");
            if (miniCount > 0) System.out.println("    Mini: " + miniCount);
            if (megaCount > 0) System.out.println("    Mega: " + megaCount);
            if (poolCount > 0) System.out.println("    Pool: " + poolCount);
        }
        System.out.println();

        // JVM Memory
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        System.out.println(TableFormatter.color("JVM Memory:", TableFormatter.YELLOW));
        System.out.println("  Used: " + formatBytes(usedMemory) + " / " + formatBytes(maxMemory));
        System.out.println("  Free: " + formatBytes(freeMemory));

        // Thread count
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        System.out.println();
        System.out.println("Active Threads: " + threadCount);

        return true;
    }

    private String formatUptime(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        if (minutes > 0 || hours > 0 || days > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");

        return sb.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), unit);
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"stats", "info"};
    }

    @Override
    public String getDescription() {
        return "Show overall system status";
    }

    @Override
    public String getUsage() {
        return "status";
    }
}