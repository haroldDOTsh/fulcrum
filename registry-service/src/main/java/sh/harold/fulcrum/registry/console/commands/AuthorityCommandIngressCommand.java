package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityCommandIngressLog;
import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;
import sh.harold.fulcrum.registry.console.TableFormatter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operator command for authority command ingress replay.
 */
public class AuthorityCommandIngressCommand implements CommandHandler {
    private final RegistryService registryService;

    /**
     * Create the authority command ingress console command.
     *
     * @param registryService registry service
     */
    public AuthorityCommandIngressCommand(RegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public boolean execute(String[] args) {
        PostgresAuthorityCommandIngressLog ingressLog = registryService.getAuthorityCommandIngressLog();
        DataAuthority.CommandPort commandPort = registryService.getAuthorityCommandPort();
        if (ingressLog == null || commandPort == null) {
            System.out.println("Central authority command ingress is not enabled");
            return false;
        }

        String action = args.length > 1 ? args[1].toLowerCase() : "list";
        return switch (action) {
            case "list" -> list(ingressLog, args);
            case "show" -> show(ingressLog, args);
            case "replay" -> replay(ingressLog, commandPort, args);
            default -> {
                System.out.println("Unknown authority command action: " + action);
                System.out.println("Usage: " + getUsage());
                yield false;
            }
        };
    }

    private boolean list(PostgresAuthorityCommandIngressLog ingressLog, String[] args) {
        int limit = args.length > 2 ? parseLimit(args[2]) : 20;
        List<PostgresAuthorityCommandIngressLog.CommandIngressEntry> entries =
            ingressLog.findReplayCandidates(limit);
        if (entries.isEmpty()) {
            System.out.println("No replayable authority command ingress rows found");
            return true;
        }

        TableFormatter table = new TableFormatter()
            .addHeaders("Command ID", "Declaration", "Status", "Reason", "Route", "Principal", "Replays", "Updated");
        for (PostgresAuthorityCommandIngressLog.CommandIngressEntry entry : entries) {
            table.addRow(
                entry.commandId().toString(),
                entry.declarationId(),
                entry.status().name(),
                entry.rejectionReason().name(),
                truncate(entry.commandDomain() + "/" + entry.partitionKey(), 32),
                truncate(entry.verifiedPrincipal(), 24),
                Integer.toString(entry.replayAttempts()),
                formatInstant(entry.updatedAt())
            );
        }
        System.out.println(table.build());
        return true;
    }

    private boolean show(PostgresAuthorityCommandIngressLog ingressLog, String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: authoritycommands show <command-id>");
            return false;
        }
        UUID commandId = parseCommandId(args[2]);
        PostgresAuthorityCommandIngressLog.CommandIngressEntry entry = ingressLog.find(commandId).orElse(null);
        if (entry == null) {
            System.out.println("Authority command ingress row not found: " + commandId);
            return false;
        }

        System.out.println("Command ID: " + entry.commandId());
        System.out.println("Declaration ID: " + entry.declarationId());
        System.out.println("Status: " + entry.status());
        System.out.println("Replayable: " + entry.replayable());
        System.out.println("Scope: " + entry.aggregateScope());
        System.out.println("Idempotency Key: " + entry.idempotencyKey());
        System.out.println("Command Domain: " + entry.commandDomain());
        System.out.println("Command Topic: " + entry.commandTopic());
        System.out.println("Partition Key: " + entry.partitionKey());
        System.out.println("Route Matches Command: " + entry.routeMatchesCommand());
        System.out.println("Claimed Actor: " + entry.claimedActor());
        System.out.println("Verified Principal: " + entry.verifiedPrincipal());
        System.out.println("Rejection Reason: " + entry.rejectionReason());
        System.out.println("Result Revision: " + entry.resultRevision());
        System.out.println("Result Message: " + nullToEmpty(entry.resultMessage()));
        System.out.println("Failure Message: " + nullToEmpty(entry.failureMessage()));
        System.out.println("Replay Attempts: " + entry.replayAttempts());
        System.out.println("Last Replayed: " + formatInstant(entry.lastReplayedAt()));
        System.out.println("Payload Hash: " + entry.payloadHash());
        System.out.println("Command Fingerprint: " + entry.commandFingerprint());
        return true;
    }

    private boolean replay(
        PostgresAuthorityCommandIngressLog ingressLog,
        DataAuthority.CommandPort commandPort,
        String[] args
    ) {
        if (args.length < 3) {
            System.out.println("Usage: authoritycommands replay <command-id>");
            return false;
        }
        UUID commandId = parseCommandId(args[2]);
        PostgresAuthorityCommandIngressLog.ReplayResult result = ingressLog.replay(commandId, commandPort)
            .toCompletableFuture()
            .join();
        System.out.println(result.message());
        if (!result.submitted()) {
            return false;
        }
        DataAuthority.CommandResult commandResult = result.commandResult();
        System.out.println("Accepted: " + commandResult.accepted());
        System.out.println("Revision: " + commandResult.revision());
        System.out.println("Rejection Reason: " + commandResult.rejectionReason());
        System.out.println("Message: " + commandResult.message());
        return commandResult.accepted();
    }

    private static UUID parseCommandId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid command id: " + value, exception);
        }
    }

    private static int parseLimit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid limit: " + value, exception);
        }
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @Override
    public String getName() {
        return "authoritycommands";
    }

    @Override
    public String[] getAliases() {
        return new String[] {"authoritycmds", "cmdingress"};
    }

    @Override
    public String getDescription() {
        return "List, inspect, or replay authority command ingress rows";
    }

    @Override
    public String getUsage() {
        return "authoritycommands [list [limit] | show <command-id> | replay <command-id>]";
    }
}
