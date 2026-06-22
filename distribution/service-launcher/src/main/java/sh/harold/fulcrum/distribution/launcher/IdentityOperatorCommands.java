package sh.harold.fulcrum.distribution.launcher;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

final class IdentityOperatorCommands {
    int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            out.print(usage());
            return FulcrumLauncher.OK;
        }
        return switch (args[0]) {
            case "list" -> list(slice(args), out);
            case "inspect" -> inspect(slice(args), out);
            default -> throw new IllegalArgumentException("Unknown fulcrum identity command: " + args[0]);
        };
    }

    private int list(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print("Usage: fulcrum identity list [--state-dir=<path>]" + System.lineSeparator());
            return FulcrumLauncher.OK;
        }
        Map<String, BundleInstallGrantRecord> latest =
                new BundleInstallGrantStateStore(stateDir(options)).latestByBundle();
        long installed = latest.values().stream()
                .filter(BundleInstallGrantRecord::active)
                .count();
        out.println("identities=" + installed);
        latest.values().stream()
                .filter(BundleInstallGrantRecord::active)
                .forEach(record -> out.println("bundle=" + record.bundleId()
                        + " status=" + record.status()
                        + " grantFingerprint=" + record.grantFingerprint()
                        + " instance=" + record.instanceId()
                        + " credential=" + record.credentialRef()));
        return FulcrumLauncher.OK;
    }

    private int inspect(String[] args, PrintStream out) {
        OperatorArguments options = parse(args);
        if (options.flag("help")) {
            out.print("Usage: fulcrum identity inspect <bundle-id> [--state-dir=<path>]" + System.lineSeparator());
            return FulcrumLauncher.OK;
        }
        String id = options.value("id")
                .or(() -> options.positionals().stream().findFirst())
                .orElseThrow(() -> new IllegalArgumentException("Missing bundle id"));
        BundleInstallGrantRecord grant = new BundleInstallGrantStateStore(stateDir(options))
                .latest(id)
                .orElse(null);
        if (grant == null) {
            out.println("bundle=" + id);
            out.println("status=missing");
            return FulcrumLauncher.OK;
        }
        out.println("bundle=" + grant.bundleId());
        out.println("status=" + grant.status());
        out.println("reason=" + grant.reason());
        out.println("grantFingerprint=" + grant.grantFingerprint());
        out.println("instanceId=" + grant.instanceId());
        out.println("credential=" + grant.credentialRef());
        out.println("evidence=" + grant.evidence());
        return FulcrumLauncher.OK;
    }

    private static OperatorArguments parse(String[] args) {
        OperatorArguments options = OperatorArguments.parse(args, Set.of("help"));
        options.rejectUnknown(Set.of("help", "state-dir", "id"));
        return options;
    }

    private static Path stateDir(OperatorArguments options) {
        return Path.of(options.value("state-dir").orElse(".fulcrum"));
    }

    private static String[] slice(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: fulcrum identity <list|inspect> [options]",
                "",
                "Commands:",
                "  list     print active install grants from the grant lifecycle ledger",
                "  inspect  print the latest grant lifecycle receipt for one bundle",
                "");
    }
}
