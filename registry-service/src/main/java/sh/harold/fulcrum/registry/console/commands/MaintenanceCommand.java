package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.api.maintenance.MaintenanceContext;
import sh.harold.fulcrum.api.maintenance.MaintenanceScope;
import sh.harold.fulcrum.api.maintenance.MaintenanceStatus;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.maintenance.MaintenanceCoordinator;
import sh.harold.fulcrum.registry.maintenance.MaintenanceSnapshot;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class MaintenanceCommand implements CommandHandler {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    private final MaintenanceCoordinator coordinator;

    public MaintenanceCommand(MaintenanceCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public boolean execute(String[] args) {
        if (coordinator == null) {
            System.out.println("Maintenance coordinator unavailable.");
            return false;
        }

        if (args.length == 1) {
            printUsage();
            return false;
        }

        String scopeToken = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(scopeToken) || "status".equals(scopeToken)) {
            return printSnapshot();
        }

        MaintenanceScope scope = resolveScope(scopeToken);
        if (scope == null) {
            printUsage();
            return false;
        }
        if (args.length < 3) {
            printUsage();
            return false;
        }

        String action = args[2].toLowerCase(Locale.ROOT);
        Flags flags;
        try {
            flags = parseFlags(args, 3);
        } catch (IllegalArgumentException ex) {
            System.out.println("Invalid flag: " + ex.getMessage());
            return false;
        }

        try {
            return switch (action) {
                case "on" -> toggle(scope, MaintenanceStatus.ON, flags);
                case "off" -> toggle(scope, MaintenanceStatus.OFF, flags);
                default -> {
                    printUsage();
                    yield false;
                }
            };
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            System.out.println("Maintenance update failed: " + cause.getMessage());
            return false;
        } catch (IllegalArgumentException ex) {
            System.out.println("Maintenance update failed: " + ex.getMessage());
            return false;
        }
    }

    private boolean toggle(MaintenanceScope scope, MaintenanceStatus status, Flags flags) {
        MaintenanceSnapshot snapshot = coordinator.updateScope(
                scope,
                status,
                flags.contextId(),
                flags.expiresAt(),
                MaintenanceCoordinator.CONSOLE_ACTOR
        ).toCompletableFuture().join();
        if (status == MaintenanceStatus.OFF) {
            System.out.println("Maintenance disabled for scope " + scope.key());
            return true;
        }
        snapshot.get(scope)
                .ifPresentOrElse(this::printContext, () -> System.out.println("No active maintenance for scope " + scope.key()));
        return true;
    }

    private boolean printSnapshot() {
        MaintenanceSnapshot snapshot = coordinator.snapshot();
        if (snapshot.isEmpty()) {
            System.out.println("No active maintenance contexts.");
            return true;
        }

        System.out.printf("%-10s %-10s %-8s %-24s%n", "SCOPE", "CONTEXT", "STATUS", "EXPIRES (UTC)");
        snapshot.all().forEach(this::printContextRow);
        return true;
    }

    private void printContext(MaintenanceContext context) {
        System.out.println("Scope   : " + context.scope().key());
        System.out.println("Context : " + context.shortId() + " (" + context.id() + ")");
        System.out.println("Status  : " + context.status());
        System.out.println("Updated : " + FORMATTER.format(context.updatedAt()));
        System.out.println("Expires : " + (context.expiresAt() == null ? "N/A" : FORMATTER.format(context.expiresAt())));
    }

    private void printContextRow(MaintenanceContext context) {
        String expires = context.expiresAt() == null ? "N/A" : FORMATTER.format(context.expiresAt());
        System.out.printf("%-10s %-10s %-8s %-24s%n",
                context.scope().key(),
                context.shortId(),
                context.status(),
                expires);
    }

    private MaintenanceScope resolveScope(String token) {
        if ("network".equalsIgnoreCase(token)) {
            return MaintenanceScope.NETWORK;
        }
        return null;
    }

    private Flags parseFlags(String[] args, int startIndex) {
        Optional<UUID> contextId = Optional.empty();
        Optional<Instant> expiresAt = Optional.empty();
        int index = startIndex;
        while (index < args.length) {
            String token = args[index];
            switch (token.toLowerCase(Locale.ROOT)) {
                case "--id" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --id");
                    }
                    contextId = Optional.of(parseUuid(args[++index]));
                }
                case "--until" -> {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("missing value for --until");
                    }
                    expiresAt = Optional.of(parseEpoch(args[++index]));
                }
                default -> throw new IllegalArgumentException("unknown flag " + token);
            }
            index++;
        }
        return new Flags(contextId, expiresAt);
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid UUID: " + raw);
        }
    }

    private Instant parseEpoch(String raw) {
        try {
            long epochSeconds = Long.parseLong(raw);
            if (epochSeconds <= 0) {
                throw new IllegalArgumentException("timestamp must be positive");
            }
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid unix timestamp: " + raw);
        }
    }

    private void printUsage() {
        System.out.println("Usage:");
        System.out.println("  maintenance network on [--until <epochSeconds>] [--id <contextId>]");
        System.out.println("  maintenance network off [--id <contextId>]");
        System.out.println("  maintenance list");
    }

    @Override
    public String getName() {
        return "maintenance";
    }

    @Override
    public String[] getAliases() {
        return new String[0];
    }

    @Override
    public String getDescription() {
        return "Toggle network maintenance gates";
    }

    @Override
    public String getUsage() {
        return "maintenance network <on|off> [flags]";
    }

    private record Flags(Optional<UUID> contextId, Optional<Instant> expiresAt) {
    }
}
