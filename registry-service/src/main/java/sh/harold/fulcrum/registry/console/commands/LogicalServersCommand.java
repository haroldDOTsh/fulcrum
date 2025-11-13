package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;
import sh.harold.fulcrum.registry.console.inspect.RedisRegistryInspector;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.slot.LogicalSlotRecord;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command that lists logical server slots tracked by the registry.
 */
public record LogicalServersCommand(RedisRegistryInspector inspector) implements CommandHandler {
    private static final int ITEMS_PER_PAGE = 15;

    public LogicalServersCommand {
        Objects.requireNonNull(inspector, "inspector");
    }

    @Override
    public boolean execute(String[] args) {
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ex) {
                System.out.println("Invalid page number: " + args[1]);
                return false;
            }
        }

        List<SlotSnapshot> snapshots = captureSnapshots();
        if (snapshots.isEmpty()) {
            System.out.println("No logical servers are currently registered.");
            return true;
        }

        int totalPages = (snapshots.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (page > totalPages) {
            System.out.println("Page " + page + " does not exist. Total pages: " + totalPages);
            return false;
        }

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, snapshots.size());
        long now = System.currentTimeMillis();

        TableFormatter table = new TableFormatter()
                .addHeaders("Family", "Server", "Slot ID", "Status", "Players", "Game", "Updated");

        for (int i = startIndex; i < endIndex; i++) {
            SlotSnapshot snapshot = snapshots.get(i);
            table.addRow(
                    snapshot.family(),
                    snapshot.serverDisplay(),
                    snapshot.slotId(),
                    formatStatus(snapshot.status()),
                    formatPlayers(snapshot.onlinePlayers(), snapshot.maxPlayers()),
                    snapshot.game(),
                    formatLastUpdated(snapshot.lastUpdated(), now)
            );
        }

        System.out.println("\nLogical Servers (Page " + page + " of " + totalPages + "):");
        System.out.println(table.build());

        if (totalPages > 1) {
            System.out.println("\nUse 'ls <page>' to view other pages");
        }

        printSummary(snapshots);
        return true;
    }

    @Override
    public String getName() {
        return "ls";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"logicalservers", "slots"};
    }

    @Override
    public String getDescription() {
        return "List logical server slots and their current state";
    }

    @Override
    public String getUsage() {
        return "ls [page]";
    }

    private List<SlotSnapshot> captureSnapshots() {
        List<SlotSnapshot> snapshots = new ArrayList<>();

        for (RedisRegistryInspector.ServerView view : inspector.fetchServers()) {
            RegisteredServerData server = view.snapshot();
            String serverDisplay = buildServerDisplay(server);
            for (LogicalSlotRecord slot : server.getSlots()) {
                Map<String, String> metadata = new HashMap<>(slot.getMetadata());
                String family = resolveFamily(slot, metadata);
                snapshots.add(new SlotSnapshot(
                        family,
                        server.getServerId(),
                        serverDisplay,
                        slot.getSlotId(),
                        slot.getStatus(),
                        slot.getOnlinePlayers(),
                        slot.getMaxPlayers(),
                        resolveGame(slot, metadata, family),
                        slot.getLastUpdated()
                ));
            }
        }

        snapshots.sort(Comparator
                .comparing((SlotSnapshot snapshot) -> snapshot.family().toLowerCase(Locale.ROOT))
                .thenComparing(SlotSnapshot::serverId)
                .thenComparing(SlotSnapshot::slotId));
        return snapshots;
    }

    private String resolveFamily(LogicalSlotRecord slot, Map<String, String> metadata) {
        String family = metadata.get("family");
        if (family != null && !family.isBlank()) {
            return family;
        }
        String slotId = slot.getSlotId();
        int dashIndex = slotId.indexOf('-');
        if (dashIndex > 0) {
            return slotId.substring(0, dashIndex);
        }
        return "unknown";
    }

    private String resolveGame(LogicalSlotRecord slot, Map<String, String> metadata, String family) {
        if (slot.getGameType() != null && !slot.getGameType().isBlank()) {
            return slot.getGameType();
        }
        String variant = metadata.get("variant");
        if (variant != null && !variant.isBlank()) {
            return variant;
        }
        return family;
    }

    private String buildServerDisplay(RegisteredServerData server) {
        String base = server.getServerId();
        String type = server.getServerType();
        String role = server.getRole();

        if (type != null && !type.isBlank()) {
            if (role != null && !role.isBlank() && !"default".equalsIgnoreCase(role)) {
                return base + " (" + type + "/" + role + ")";
            }
            return base + " (" + type + ")";
        }
        if (role != null && !role.isBlank() && !"default".equalsIgnoreCase(role)) {
            return base + " (" + role + ")";
        }
        return base;
    }

    private String formatStatus(SlotLifecycleStatus status) {
        if (status == null) {
            return TableFormatter.color("UNKNOWN", TableFormatter.YELLOW);
        }
        return switch (status) {
            case AVAILABLE -> TableFormatter.color("AVAILABLE", TableFormatter.BRIGHT_GREEN);
            case PROVISIONING -> TableFormatter.color("PROVISIONING", TableFormatter.YELLOW);
            case ALLOCATED -> TableFormatter.color("ALLOCATED", TableFormatter.CYAN);
            case IN_GAME -> TableFormatter.color("IN_GAME", TableFormatter.GREEN);
            case COOLDOWN -> TableFormatter.color("COOLDOWN", TableFormatter.BLUE);
            case FAULTED -> TableFormatter.color("FAULTED", TableFormatter.BRIGHT_RED);
        };
    }

    private String formatPlayers(int online, int max) {
        if (max <= 0) {
            return online + "/?";
        }
        return online + "/" + max;
    }

    private String formatLastUpdated(long lastUpdated, long now) {
        if (lastUpdated <= 0) {
            return "unknown";
        }
        long delta = Math.max(0, now - lastUpdated);
        long seconds = delta / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        seconds %= 60;
        if (minutes < 60) {
            if (seconds == 0) {
                return minutes + "m ago";
            }
            return minutes + "m " + seconds + "s ago";
        }
        long hours = minutes / 60;
        minutes %= 60;
        if (hours < 24) {
            return hours + "h " + minutes + "m ago";
        }
        long days = hours / 24;
        hours %= 24;
        return days + "d " + hours + "h ago";
    }

    private void printSummary(List<SlotSnapshot> snapshots) {
        int totalSlots = snapshots.size();
        int totalPlayers = snapshots.stream().mapToInt(SlotSnapshot::onlinePlayers).sum();
        int totalCapacity = snapshots.stream()
                .mapToInt(snapshot -> Math.max(0, snapshot.maxPlayers()))
                .sum();

        Map<SlotLifecycleStatus, Long> statusCounts = snapshots.stream()
                .collect(Collectors.groupingBy(
                        snapshot -> Objects.requireNonNullElse(snapshot.status(), SlotLifecycleStatus.PROVISIONING),
                        Collectors.counting()
                ));
        long distinctFamilies = snapshots.stream()
                .map(SlotSnapshot::family)
                .distinct()
                .count();

        System.out.println("\nSlot Statistics:");
        System.out.println("  Families tracked: " + distinctFamilies);
        System.out.println("  Total logical servers: " + totalSlots);
        System.out.println("  Players online: " + totalPlayers
                + (totalCapacity > 0 ? " / " + totalCapacity : ""));
        for (SlotLifecycleStatus status : SlotLifecycleStatus.values()) {
            long count = statusCounts.getOrDefault(status, 0L);
            if (count > 0) {
                System.out.println("  " + status + ": " + count);
            }
        }
    }

    private record SlotSnapshot(String family,
                                String serverId,
                                String serverDisplay,
                                String slotId,
                                SlotLifecycleStatus status,
                                int onlinePlayers,
                                int maxPlayers,
                                String game,
                                long lastUpdated) {
    }
}
