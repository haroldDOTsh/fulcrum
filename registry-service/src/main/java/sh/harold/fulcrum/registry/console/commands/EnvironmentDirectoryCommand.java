package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.environment.EnvironmentDirectoryDocument;
import sh.harold.fulcrum.registry.environment.EnvironmentDirectoryManager;

import java.util.List;

public final class EnvironmentDirectoryCommand implements CommandHandler {
    private final EnvironmentDirectoryManager manager;

    public EnvironmentDirectoryCommand(EnvironmentDirectoryManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean execute(String[] args) {
        if (manager == null) {
            System.out.println("Environment directory manager is unavailable.");
            return false;
        }

        if (args.length < 2) {
            printUsage();
            return false;
        }

        return switch (args[1].toLowerCase()) {
            case "list" -> handleList();
            case "show" -> handleShow(args);
            case "refresh" -> handleRefresh();
            default -> {
                System.out.println("Unknown subcommand: " + args[1]);
                printUsage();
                yield false;
            }
        };
    }

    private boolean handleList() {
        List<EnvironmentDirectoryDocument> environments = manager.listEnvironments();
        if (environments.isEmpty()) {
            System.out.println("No environments are registered.");
            return true;
        }

        System.out.printf("%-16s %-28s %-10s%n", "ENVIRONMENT", "TAG", "MODULES");
        for (EnvironmentDirectoryDocument doc : environments) {
            System.out.printf("%-16s %-28s %-10d%n",
                    doc.id(),
                    doc.tag(),
                    doc.modules().size());
        }
        return true;
    }

    private boolean handleShow(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: environment show <environmentId>");
            return false;
        }
        String id = args[2];
        return manager.getEnvironment(id)
                .map(doc -> {
                    System.out.println("Environment: " + doc.id());
                    System.out.println(" Tag        : " + doc.tag());
                    System.out.println(" Modules    : " + String.join(", ", doc.modules()));
                    if (!doc.description().isBlank()) {
                        System.out.println(" Description: " + doc.description());
                    }
                    return true;
                })
                .orElseGet(() -> {
                    System.out.println("Environment '" + id + "' not found.");
                    return false;
                });
    }

    private boolean handleRefresh() {
        manager.refreshDirectory(true);
        System.out.println("Environment directory refreshed from MongoDB.");
        return true;
    }

    private void printUsage() {
        System.out.println("Usage: environment <list|show|refresh> [...]");
    }

    @Override
    public String getName() {
        return "environment";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"envdir"};
    }

    @Override
    public String getDescription() {
        return "Inspect and refresh the registry environment directory";
    }

    @Override
    public String getUsage() {
        return "environment <list|show|refresh>";
    }
}
