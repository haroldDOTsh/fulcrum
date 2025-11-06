package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.proxy.ProxyIdentifier;
import sh.harold.fulcrum.registry.proxy.ProxyRegistry;
import sh.harold.fulcrum.registry.proxy.RegisteredProxyData;
import sh.harold.fulcrum.registry.server.RegisteredServerData;
import sh.harold.fulcrum.registry.server.ServerRegistry;
import sh.harold.fulcrum.registry.shutdown.ShutdownIntentManager;
import sh.harold.fulcrum.registry.shutdown.ShutdownIntentManager.ServiceType;
import sh.harold.fulcrum.registry.shutdown.ShutdownIntentManager.ShutdownTarget;

import java.util.*;

/**
 * CLI entry point for orchestrating graceful shutdown intents.
 */
public final class ShutdownCommand implements CommandHandler {
    private final ShutdownIntentManager manager;
    private final ServerRegistry serverRegistry;
    private final ProxyRegistry proxyRegistry;

    public ShutdownCommand(ShutdownIntentManager manager,
                           ServerRegistry serverRegistry,
                           ProxyRegistry proxyRegistry) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.serverRegistry = Objects.requireNonNull(serverRegistry, "serverRegistry");
        this.proxyRegistry = Objects.requireNonNull(proxyRegistry, "proxyRegistry");
    }

    @Override
    public boolean execute(String[] args) {
        if (args.length < 2) {
            printUsage();
            return false;
        }

        String scope = args[1].toLowerCase(Locale.ROOT);
        return switch (scope) {
            case "all" -> handleAll(args);
            case "family" -> handleFamily(args);
            case "service" -> handleService(args);
            case "cancel" -> handleCancel(args);
            default -> {
                printUsage();
                yield false;
            }
        };
    }

    @Override
    public String getName() {
        return "shutdown";
    }

    @Override
    public String[] getAliases() {
        return new String[0];
    }

    @Override
    public String getDescription() {
        return "Coordinate graceful shutdowns across proxies and runtimes";
    }

    @Override
    public String getUsage() {
        return "shutdown <all|family|service|cancel> ...";
    }

    private boolean handleAll(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: shutdown all <seconds> [--reason <text>] [--force]");
            return false;
        }
        int seconds = parseSeconds(args[2]);
        if (seconds <= 0) {
            return false;
        }
        Flags flags = parseFlags(args, 3);

        List<ShutdownTarget> targets = new ArrayList<>();
        serverRegistry.getAllServers().forEach(server -> {
            if (server.getStatus() != RegisteredServerData.Status.DEAD) {
                targets.add(new ShutdownTarget(server.getServerId(), ServiceType.BACKEND));
            }
        });
        proxyRegistry.getAllProxies().forEach(proxy -> {
            if (proxy.getStatus() != RegisteredProxyData.Status.DEAD) {
                targets.add(new ShutdownTarget(proxy.getProxyIdString(), ServiceType.PROXY));
            }
        });

        if (targets.isEmpty()) {
            System.out.println("No active services to shutdown.");
            return false;
        }

        manager.createIntent(targets, seconds, flags.reason, "lobby", flags.force);
        return true;
    }

    private boolean handleFamily(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: shutdown family <environmentId> <seconds> [--reason <text>] [--force]");
            return false;
        }
        String environmentId = args[2];
        int seconds = parseSeconds(args[3]);
        if (seconds <= 0) {
            return false;
        }
        Flags flags = parseFlags(args, 4);

        List<ShutdownTarget> targets = serverRegistry.getServersByRole(environmentId).stream()
                .filter(server -> server.getStatus() != RegisteredServerData.Status.DEAD)
                .map(server -> new ShutdownTarget(server.getServerId(), ServiceType.BACKEND))
                .toList();

        if (targets.isEmpty()) {
            System.out.printf("No active backends found for environment '%s'.%n", environmentId);
            return false;
        }

        manager.createIntent(targets, seconds, flags.reason, "lobby", flags.force);
        return true;
    }

    private boolean handleService(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: shutdown service <serviceId> <seconds> [--reason <text>] [--force]");
            return false;
        }

        String serviceId = args[2];
        int seconds = parseSeconds(args[3]);
        if (seconds <= 0) {
            return false;
        }
        Flags flags = parseFlags(args, 4);

        Optional<ShutdownTarget> target = resolveService(serviceId);
        if (target.isEmpty()) {
            System.out.printf("Service '%s' is not registered.%n", serviceId);
            return false;
        }

        manager.createIntent(List.of(target.get()), seconds, flags.reason, "lobby", flags.force);
        return true;
    }

    private boolean handleCancel(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: shutdown cancel <intentId>");
            return false;
        }
        String intentId = args[2];
        boolean cancelled = manager.cancelIntent(intentId, "console");
        if (!cancelled) {
            System.out.printf("Intent '%s' is not active.%n", intentId);
        }
        return cancelled;
    }

    private Optional<ShutdownTarget> resolveService(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return Optional.empty();
        }
        if (ProxyIdentifier.isValid(serviceId)) {
            RegisteredProxyData proxy = proxyRegistry.getProxy(serviceId);
            if (proxy != null && proxy.getStatus() != RegisteredProxyData.Status.DEAD) {
                return Optional.of(new ShutdownTarget(serviceId, ServiceType.PROXY));
            }
        }

        RegisteredServerData server = serverRegistry.getServer(serviceId);
        if (server != null && server.getStatus() != RegisteredServerData.Status.DEAD) {
            return Optional.of(new ShutdownTarget(serviceId, ServiceType.BACKEND));
        }

        return Optional.empty();
    }

    private int parseSeconds(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                System.out.println("Countdown must be positive seconds.");
                return -1;
            }
            return value;
        } catch (NumberFormatException ex) {
            System.out.println("Invalid countdown: " + raw);
            return -1;
        }
    }

    private Flags parseFlags(String[] args, int startIndex) {
        String reason = "Scheduled maintenance";
        boolean force = false;
        int index = startIndex;
        while (index < args.length) {
            String token = args[index];
            if ("--reason".equalsIgnoreCase(token)) {
                if (index + 1 >= args.length) {
                    System.out.println("--reason requires a value");
                    break;
                }
                StringBuilder builder = new StringBuilder();
                for (int i = index + 1; i < args.length; i++) {
                    if (args[i].startsWith("--")) {
                        break;
                    }
                    if (builder.length() > 0) {
                        builder.append(' ');
                    }
                    builder.append(args[i]);
                    index = i;
                }
                reason = builder.length() > 0 ? builder.toString() : reason;
            } else if ("--force".equalsIgnoreCase(token)) {
                force = true;
            }
            index++;
        }
        return new Flags(reason, force);
    }

    private void printUsage() {
        System.out.println("Shutdown command usage:");
        System.out.println("  shutdown all <seconds> [--reason <text>] [--force]");
        System.out.println("  shutdown family <environmentId> <seconds> [--reason <text>] [--force]");
        System.out.println("  shutdown service <serviceId> <seconds> [--reason <text>] [--force]");
        System.out.println("  shutdown cancel <intentId>");
    }

    private record Flags(String reason, boolean force) {
    }
}
