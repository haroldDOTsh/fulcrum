package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.api.data.impl.authority.PostgresAuthorityStateRestoreDrill;
import sh.harold.fulcrum.registry.RegistryService;
import sh.harold.fulcrum.registry.console.CommandHandler;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator command for authority state restore drills.
 */
public class AuthorityStateRestoreCommand implements CommandHandler {
    private final RegistryService registryService;

    /**
     * Create the authority state restore command.
     *
     * @param registryService registry service
     */
    public AuthorityStateRestoreCommand(RegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public boolean execute(String[] args) {
        PostgresAuthorityStateRestoreDrill restoreDrill = registryService.getAuthorityStateRestoreDrill();
        if (restoreDrill == null) {
            System.out.println("Central authority state restore drill is not enabled");
            return false;
        }
        if (args.length < 3) {
            System.out.println("Usage: " + getUsage());
            return false;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        String aggregateScope = args[2];
        String reason = args.length > 3
            ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
            : "operator-" + action;
        PostgresAuthorityStateRestoreDrill.RestoreRunResult result = switch (action) {
            case "verify" -> restoreDrill.verifyLatestSnapshot(aggregateScope, reason);
            case "restore" -> restoreDrill.restoreLatestSnapshot(aggregateScope, reason);
            default -> {
                System.out.println("Unknown authority restore action: " + action);
                System.out.println("Usage: " + getUsage());
                yield null;
            }
        };
        if (result == null) {
            return false;
        }

        printResult(result);
        return result.clean();
    }

    private static void printResult(PostgresAuthorityStateRestoreDrill.RestoreRunResult result) {
        PostgresAuthorityStateRestoreDrill.RestoreEvidence evidence = result.evidence();
        System.out.println("Authority state restore drill result");
        System.out.println("Run ID: " + result.restoreRunId());
        System.out.println("Scope: " + result.aggregateScope());
        System.out.println("Status: " + result.status());
        System.out.println("Clean: " + result.clean());
        System.out.println("Restored: " + result.restored());
        System.out.println("Source Changelog ID: " + value(result.sourceChangelogId()));
        System.out.println("Source Event ID: " + value(result.sourceEventId()));
        System.out.println("Source Revision: " + value(result.sourceRevision()));
        System.out.println("Snapshot Revision: " + value(result.snapshotRevision()));
        System.out.println("Message: " + value(result.message()));
        System.out.println("Restore Source: " + value(evidence == null ? null : evidence.restoreSource()));
        System.out.println("Source State Fingerprint: "
            + value(evidence == null ? null : evidence.sourceStateFingerprint()));
        System.out.println("Snapshot State Fingerprint: "
            + value(evidence == null ? null : evidence.snapshotStateFingerprint()));
        System.out.println("Source Event Chain Hash: "
            + value(evidence == null ? null : evidence.sourceEventChainHash()));
        System.out.println("Verification Fingerprint: " + result.verificationFingerprint());
    }

    private static String value(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static String value(Long value) {
        return value == null ? "" : value.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String getName() {
        return "authorityrestore";
    }

    @Override
    public String[] getAliases() {
        return new String[] {"authrestore", "staterestore"};
    }

    @Override
    public String getDescription() {
        return "Verify or restore an authority state snapshot from the changelog";
    }

    @Override
    public String getUsage() {
        return "authorityrestore <verify|restore> <aggregate-scope> [reason]";
    }
}
