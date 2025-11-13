package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.network.NetworkConfigManager;
import sh.harold.fulcrum.registry.network.NetworkProfileValidationException;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

public record NetworkConfigCommand(NetworkConfigManager manager) implements CommandHandler {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    public NetworkConfigCommand {
        Objects.requireNonNull(manager, "manager");
    }

    @Override
    public boolean execute(String[] args) {
        if (manager == null) {
            System.out.println("Network configuration manager is unavailable.");
            return false;
        }

        if (args.length < 2) {
            printUsage();
            return false;
        }

        String action = args[1].toLowerCase();
        return switch (action) {
            case "list" -> handleList();
            case "apply" -> handleApply(args);
            case "refresh" -> handleRefresh();
            case "active" -> handleActive();
            default -> {
                System.out.println("Unknown subcommand: " + action);
                printUsage();
                yield false;
            }
        };
    }

    private boolean handleList() {
        List<NetworkConfigManager.NetworkProfileSummary> summaries = manager.listProfiles();
        if (summaries.isEmpty()) {
            System.out.println("No network profiles found.");
            return true;
        }

        System.out.printf("%-16s %-24s %-8s %-24s%n", "PROFILE", "TAG", "ACTIVE", "UPDATED AT");
        for (NetworkConfigManager.NetworkProfileSummary summary : summaries) {
            System.out.printf(
                    "%-16s %-24s %-8s %-24s%n",
                    summary.profileId(),
                    summary.tag(),
                    summary.active() ? "yes" : "no",
                    FORMATTER.format(summary.updatedAt())
            );
        }
        return true;
    }

    private boolean handleApply(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: networkconfig apply <profileId>");
            return false;
        }
        String profileId = args[2];

        try {
            var view = manager.applyProfile(profileId);
            String tag = view.getString("tag").orElse(view.profileId());
            System.out.println("Applied profile '" + view.profileId() + "' (" + tag + ")");
            return true;
        } catch (IllegalArgumentException ex) {
            System.out.println("Failed to apply profile: " + ex.getMessage());
            return false;
        } catch (NetworkProfileValidationException ex) {
            System.out.println("Profile failed validation:");
            ex.getErrors().forEach(error -> System.out.println(" - " + error));
            return false;
        } catch (Exception ex) {
            System.out.println("Unexpected error applying profile: " + ex.getMessage());
            return false;
        }
    }

    private boolean handleRefresh() {
        manager.refreshProfiles();
        System.out.println("Network profiles refreshed from MongoDB.");
        return true;
    }

    private boolean handleActive() {
        return manager.getActiveProfileView()
                .map(profile -> {
                    String tag = profile.getString("tag").orElse(profile.profileId());
                    String serverIp = profile.getString("serverIp").orElse("unknown");
                    List<String> motd = profile.getStringList("motd");
                    System.out.println("Active profile: " + profile.profileId());
                    System.out.println(" Tag      : " + tag);
                    System.out.println(" Updated  : " + FORMATTER.format(profile.updatedAt()));
                    System.out.println(" Server IP: " + serverIp);
                    System.out.println(" MOTD     : " + motd);
                    return true;
                })
                .orElseGet(() -> {
                    System.out.println("No active network profile is currently loaded.");
                    return false;
                });
    }

    private void printUsage() {
        System.out.println("Usage: networkconfig <list|apply|refresh|active> [...]");
    }

    @Override
    public String getName() {
        return "networkconfig";
    }

    @Override
    public String[] getAliases() {
        return new String[]{"netcfg"};
    }

    @Override
    public String getDescription() {
        return "Inspect and promote shared network profiles";
    }

    @Override
    public String getUsage() {
        return "networkconfig <list|apply|refresh|active>";
    }
}
