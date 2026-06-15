package sh.harold.fulcrum.api.data.impl.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Boot-time evidence that the registry-owned Data Authority is ready to accept traffic.
 */
public final class DataAuthorityCustodyPreflight {
    private DataAuthorityCustodyPreflight() {
    }

    public static Check check(String name, Runnable validator) {
        return new Check(name, validator);
    }

    public static Report require(String ownerNode, String principalSource, Collection<Check> checks) {
        Report report = inspect(ownerNode, principalSource, checks);
        if (!report.passed()) {
            throw new IllegalStateException(report.failureMessage());
        }
        return report;
    }

    public static Report inspect(String ownerNode, String principalSource, Collection<Check> checks) {
        List<CheckResult> results = new ArrayList<>();
        for (Check check : normalizedChecks(checks)) {
            results.add(check.run());
        }
        return new Report(
            normalize(ownerNode, "ownerNode"),
            normalize(principalSource, "principalSource"),
            AuthorityCommandManifest.fingerprint(),
            DataAuthorityReadContracts.fingerprint(),
            results
        );
    }

    private static List<Check> normalizedChecks(Collection<Check> checks) {
        Collection<Check> safeChecks = checks == null ? List.of() : checks;
        List<Check> normalized = new ArrayList<>();
        for (Check check : safeChecks) {
            if (check != null) {
                normalized.add(check);
            }
        }
        normalized.sort(Comparator.comparing(Check::name));
        return List.copyOf(normalized);
    }

    private static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to fingerprint authority custody preflight", exception);
        }
    }

    private static String shortFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "<missing>";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record Check(String name, Runnable validator) {
        public Check {
            name = normalize(name, "name");
            validator = Objects.requireNonNull(validator, "validator");
        }

        private CheckResult run() {
            try {
                validator.run();
                return new CheckResult(name, true, "ok");
            } catch (RuntimeException exception) {
                return new CheckResult(name, false, exception.getMessage());
            }
        }
    }

    public record CheckResult(String name, boolean passed, String reason) {
        public CheckResult {
            name = normalize(name, "name");
            reason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
        }
    }

    public record Report(
        String ownerNode,
        String principalSource,
        String commandContractFingerprint,
        String readContractFingerprint,
        List<CheckResult> checks
    ) {
        public Report {
            ownerNode = normalize(ownerNode, "ownerNode");
            principalSource = normalize(principalSource, "principalSource");
            commandContractFingerprint = normalize(commandContractFingerprint, "commandContractFingerprint");
            readContractFingerprint = normalize(readContractFingerprint, "readContractFingerprint");
            checks = checks == null ? List.of() : List.copyOf(checks);
        }

        public boolean passed() {
            return checks.stream().allMatch(CheckResult::passed);
        }

        public String custodyFingerprint() {
            StringBuilder material = new StringBuilder()
                .append("ownerNode=").append(ownerNode).append('\n')
                .append("principalSource=").append(principalSource).append('\n')
                .append("commandContractFingerprint=").append(commandContractFingerprint).append('\n')
                .append("readContractFingerprint=").append(readContractFingerprint).append('\n')
                .append("passed=").append(passed()).append('\n');
            checks.stream()
                .sorted(Comparator.comparing(CheckResult::name))
                .forEach(check -> material
                    .append("check=")
                    .append(check.name()).append('|')
                    .append(check.passed()).append('|')
                    .append(check.reason()).append('\n'));
            return sha256(material.toString());
        }

        public String summary() {
            return "ownerNode=" + ownerNode
                + ", principalSource=" + principalSource
                + ", passed=" + passed()
                + ", commandContractFingerprint=" + shortFingerprint(commandContractFingerprint)
                + ", readContractFingerprint=" + shortFingerprint(readContractFingerprint)
                + ", custodyFingerprint=" + custodyFingerprint();
        }

        private String failureMessage() {
            StringBuilder builder = new StringBuilder()
                .append("Data Authority custody preflight failed")
                .append(" (custodyFingerprint=")
                .append(custodyFingerprint())
                .append("):");
            for (CheckResult check : checks.stream()
                .filter(result -> !result.passed())
                .sorted(Comparator.comparing(CheckResult::name))
                .toList()) {
                builder.append(System.lineSeparator())
                    .append("- ")
                    .append(check.name())
                    .append(": ")
                    .append(check.reason());
            }
            return builder.toString();
        }
    }
}
