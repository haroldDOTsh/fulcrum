package sh.harold.fulcrum.distribution.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class BundleInstallGrantStateStore {
    static final String FILE_NAME = "bundle-install-grants.jsonl";

    private final Path stateDir;

    BundleInstallGrantStateStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    BundleInstallGrantLifecycleReceipt recordInstall(
            DeclaredBundle bundle,
            IssuedBundleGrant grant,
            String reconcileStatus,
            Instant now) {
        String grantStatus = grantStatusFor(reconcileStatus);
        String reason = grantReasonFor(grantStatus, reconcileStatus);
        Optional<BundleInstallGrantRecord> latest = latest(bundle.id());
        if (latest.filter(record -> sameInstallGrantRecord(record, grant, grantStatus, reason)).isPresent()) {
            return BundleInstallGrantLifecycleReceipt.fromRecord(latest.orElseThrow());
        }
        BundleInstallGrantRecord record = BundleInstallGrantRecord.installed(
                bundle,
                grant,
                grantStatus,
                reason,
                now);
        append(record);
        return BundleInstallGrantLifecycleReceipt.fromRecord(record);
    }

    BundleInstallGrantLifecycleReceipt revoke(String bundleId, String reason, Instant now) {
        Optional<BundleInstallGrantRecord> latest = latest(bundleId);
        if (latest.isEmpty()) {
            return BundleInstallGrantLifecycleReceipt.absent("grant-already-absent");
        }
        BundleInstallGrantRecord existing = latest.orElseThrow();
        if (existing.revoked()) {
            return BundleInstallGrantLifecycleReceipt.fromRecord(existing);
        }
        BundleInstallGrantRecord revoked = existing.revoked(reason, now);
        append(revoked);
        return BundleInstallGrantLifecycleReceipt.fromRecord(revoked);
    }

    Path append(BundleInstallGrantRecord record) {
        try {
            Files.createDirectories(stateDir);
            Path file = file();
            Files.writeString(
                    file,
                    record.toJson() + System.lineSeparator(),
                    Files.exists(file)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write bundle install grant state", exception);
        }
    }

    List<BundleInstallGrantRecord> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<BundleInstallGrantRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    records.add(BundleInstallGrantRecord.fromJson(line));
                }
            }
            return records;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle install grant state", exception);
        }
    }

    Optional<BundleInstallGrantRecord> latest(String bundleId) {
        List<BundleInstallGrantRecord> records = read();
        for (int index = records.size() - 1; index >= 0; index--) {
            BundleInstallGrantRecord record = records.get(index);
            if (record.bundleId().equals(bundleId)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    Map<String, BundleInstallGrantRecord> latestByBundle() {
        Map<String, BundleInstallGrantRecord> latest = new LinkedHashMap<>();
        read().forEach(record -> latest.put(record.bundleId(), record));
        return latest;
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }

    private static String grantStatusFor(String reconcileStatus) {
        return switch (reconcileStatus) {
            case "RUNNING", "STARTED", "STAGED" -> "ACTIVE";
            case "RENDERED" -> "ISSUED";
            default -> "REVOKED";
        };
    }

    private static String grantReasonFor(String grantStatus, String reconcileStatus) {
        return switch (grantStatus) {
            case "ACTIVE" -> "grant-active-for-" + reconcileStatus.toLowerCase(java.util.Locale.ROOT);
            case "ISSUED" -> "grant-issued-for-rendered-instance";
            case "REVOKED" -> "grant-revoked-after-"
                    + reconcileStatus.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            default -> throw new IllegalArgumentException("unsupported grant status: " + grantStatus);
        };
    }

    private static boolean sameInstallGrantRecord(
            BundleInstallGrantRecord record,
            IssuedBundleGrant grant,
            String status,
            String reason) {
        return record.status().equals(status)
                && record.reason().equals(reason)
                && record.grantFingerprint().equals(grant.grantFingerprint())
                && record.instanceId().equals(grant.securityContext().identity().instanceId().value())
                && record.credentialRef().equals(grant.securityContext().credentialRef());
    }
}
