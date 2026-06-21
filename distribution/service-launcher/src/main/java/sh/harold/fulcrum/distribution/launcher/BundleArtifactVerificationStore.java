package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BundleArtifactVerificationStore implements BundleArtifactVerificationPort {
    static final String FILE_NAME = "artifact-verifications.jsonl";

    private final Path stateDir;

    BundleArtifactVerificationStore(Path stateDir) {
        this.stateDir = java.util.Objects.requireNonNull(stateDir, "stateDir");
    }

    Path recordVerified(DeclaredBundle bundle, String evidence) {
        BundleArtifactVerificationRecord record = new BundleArtifactVerificationRecord(
                bundle.id(),
                sourceKind(bundle.artifactRef()),
                bundle.artifactRef(),
                bundle.digest(),
                true,
                nonBlank(evidence, "--signature-evidence"));
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
            throw new UncheckedIOException("Could not write artifact verification evidence", exception);
        }
    }

    @Override
    public Optional<AuthorityArtifactVerificationEvidence> verify(DeclaredBundle bundle) {
        List<BundleArtifactVerificationRecord> records = read();
        for (int index = records.size() - 1; index >= 0; index--) {
            BundleArtifactVerificationRecord record = records.get(index);
            if (record.matches(bundle)) {
                return Optional.of(new AuthorityArtifactVerificationEvidence(
                        record.verified(),
                        record.sourceKind(),
                        record.sourceReference(),
                        record.digest(),
                        record.evidence()));
            }
        }
        return Optional.empty();
    }

    Path file() {
        return stateDir.resolve(FILE_NAME);
    }

    private List<BundleArtifactVerificationRecord> read() {
        Path file = file();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<BundleArtifactVerificationRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    records.add(BundleArtifactVerificationRecord.fromJson(line));
                }
            }
            return records;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read artifact verification evidence", exception);
        }
    }

    private static String sourceKind(String artifactRef) {
        if (artifactRef.startsWith("oci://")) {
            return "OCI";
        }
        if (artifactRef.startsWith("private-oci://")) {
            return "PRIVATE_OCI";
        }
        return "IMPORTED";
    }

    private static String nonBlank(String value, String label) {
        String checked = java.util.Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private record BundleArtifactVerificationRecord(
            String bundleId,
            String sourceKind,
            String sourceReference,
            String digest,
            boolean verified,
            String evidence) {
        boolean matches(DeclaredBundle bundle) {
            return verified
                    && bundleId.equals(bundle.id())
                    && sourceReference.equals(bundle.artifactRef())
                    && digest.equals(bundle.digest());
        }

        String toJson() {
            return "{"
                    + "\"bundleId\":\"" + escape(bundleId) + "\","
                    + "\"sourceKind\":\"" + escape(sourceKind) + "\","
                    + "\"sourceReference\":\"" + escape(sourceReference) + "\","
                    + "\"digest\":\"" + escape(digest) + "\","
                    + "\"verified\":" + verified + ","
                    + "\"evidence\":\"" + escape(evidence) + "\""
                    + "}";
        }

        static BundleArtifactVerificationRecord fromJson(String json) {
            return new BundleArtifactVerificationRecord(
                    field(json, "bundleId"),
                    field(json, "sourceKind"),
                    field(json, "sourceReference"),
                    field(json, "digest"),
                    booleanField(json, "verified"),
                    field(json, "evidence"));
        }

        private static String field(String json, String name) {
            String marker = "\"" + name + "\":\"";
            int start = json.indexOf(marker);
            if (start < 0) {
                throw new IllegalArgumentException("verification record missing field: " + name);
            }
            int valueStart = start + marker.length();
            int end = json.indexOf('"', valueStart);
            if (end < 0) {
                throw new IllegalArgumentException("verification record has unterminated field: " + name);
            }
            return unescape(json.substring(valueStart, end));
        }

        private static boolean booleanField(String json, String name) {
            String marker = "\"" + name + "\":";
            int start = json.indexOf(marker);
            if (start < 0) {
                throw new IllegalArgumentException("verification record missing boolean field: " + name);
            }
            int valueStart = start + marker.length();
            if (json.startsWith("true", valueStart)) {
                return true;
            }
            if (json.startsWith("false", valueStart)) {
                return false;
            }
            throw new IllegalArgumentException("verification record has invalid boolean field: " + name);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
