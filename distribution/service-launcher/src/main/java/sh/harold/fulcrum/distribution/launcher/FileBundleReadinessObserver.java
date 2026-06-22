package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

final class FileBundleReadinessObserver implements BundleReadinessObserver {
    private static final String READY_FILE_NAME = "backend.ready";

    private final Duration timeout;
    private final Duration pollInterval;

    FileBundleReadinessObserver(Duration timeout, Duration pollInterval) {
        this.timeout = nonNegative(Objects.requireNonNull(timeout, "timeout"), "timeout");
        this.pollInterval = positive(Objects.requireNonNull(pollInterval, "pollInterval"), "pollInterval");
    }

    @Override
    public BundleReadinessReceipt observe(
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            String launchNonce,
            Instant now) {
        BundleRenderedInstance checkedRendered = Objects.requireNonNull(rendered, "rendered");
        BundleInstanceManifest checkedManifest = Objects.requireNonNull(manifest, "manifest");
        String checkedLaunchNonce = BundleLaunchNonces.require(launchNonce);
        Objects.requireNonNull(now, "now");
        Path readyFile = checkedRendered.workDir().resolve("runtime").resolve(READY_FILE_NAME);
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (Files.exists(readyFile)) {
                return readAndValidate(readyFile, checkedRendered, checkedManifest, checkedLaunchNonce);
            }
            if (System.nanoTime() >= deadline) {
                return BundleReadinessReceipt.pending("readiness-file-missing");
            }
            sleep();
        }
    }

    private BundleReadinessReceipt readAndValidate(
            Path readyFile,
            BundleRenderedInstance rendered,
            BundleInstanceManifest manifest,
            String launchNonce) {
        try {
            ReadinessDocument document = ReadinessDocument.parse(Files.readString(readyFile));
            Optional<String> rejection = rejectionReason(document, manifest, launchNonce);
            String evidence = evidence(readyFile, rendered, document);
            if (rejection.isPresent()) {
                return BundleReadinessReceipt.rejected(rejection.orElseThrow(), evidence);
            }
            return BundleReadinessReceipt.ready(document.required("receiptId"), evidence);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read bundle readiness evidence", exception);
        } catch (IllegalArgumentException exception) {
            return BundleReadinessReceipt.rejected(
                    "readiness-evidence-malformed",
                    "readyFile=" + value(readyFile)
                            + "|failureDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(exception.getMessage()));
        }
    }

    private static Optional<String> rejectionReason(
            ReadinessDocument document,
            BundleInstanceManifest manifest,
            String launchNonce) {
        if (!"ready".equals(document.required("status"))) {
            return Optional.of("readiness-status-not-ready");
        }
        if (!manifest.bundleDigest().equals(document.required("bundleDigest"))) {
            return Optional.of("readiness-bundle-digest-mismatch");
        }
        if (!manifest.principalId().equals(document.required("principalId"))) {
            return Optional.of("readiness-principal-mismatch");
        }
        if (!manifest.grantFingerprint().equals(document.required("grantFingerprint"))) {
            return Optional.of("readiness-grant-fingerprint-mismatch");
        }
        Optional<String> bootNonce = document.optional("bootNonce");
        if (bootNonce.isEmpty()) {
            return Optional.of("readiness-launch-nonce-missing");
        }
        if (!launchNonce.equals(bootNonce.orElseThrow())) {
            return Optional.of("readiness-launch-nonce-mismatch");
        }
        if (document.optional("receiptId").isEmpty()) {
            return Optional.of("readiness-registration-receipt-missing");
        }
        if (document.optional("registrationSignature").isEmpty()) {
            return Optional.of("readiness-registration-signature-missing");
        }
        if (!document.digestValid()) {
            return Optional.of("readiness-evidence-digest-mismatch");
        }
        return Optional.empty();
    }

    private static String evidence(Path readyFile, BundleRenderedInstance rendered, ReadinessDocument document) {
        return "readyFile=" + value(readyFile)
                + "|manifestHash=" + value(rendered.manifestHash())
                + "|receiptId=" + value(document.optional("receiptId").orElse("missing"))
                + "|evidenceDigest=" + value(document.optional("evidenceDigest").orElse("missing"))
                + "|bootNonceDigest=" + document.optional("bootNonce")
                .map(AuthorityBackendDescriptorDigests::sha256Hex)
                .orElse("missing")
                + "|registrationSignature=" + value(document.optional("registrationSignature").orElse("missing"));
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bundle readiness observation interrupted", exception);
        }
    }

    private static Duration nonNegative(Duration value, String name) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String value(Path path) {
        return value(path.toString());
    }

    private static String value(String value) {
        return Objects.requireNonNull(value, "value")
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "/");
    }

    private record ReadinessDocument(LinkedHashMap<String, String> fields) {
        private ReadinessDocument {
            fields = new LinkedHashMap<>(Objects.requireNonNull(fields, "fields"));
        }

        static ReadinessDocument parse(String document) {
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            for (String line : Objects.requireNonNull(document, "document").split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException("readiness evidence contains malformed line");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (fields.put(key, value) != null) {
                    throw new IllegalArgumentException("readiness evidence contains duplicate field: " + key);
                }
            }
            return new ReadinessDocument(fields);
        }

        String required(String name) {
            String value = fields.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("readiness evidence missing field: " + name);
            }
            return value;
        }

        Optional<String> optional(String name) {
            String value = fields.get(name);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }

        boolean digestValid() {
            String expected = required("evidenceDigest");
            String canonical = fields.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("evidenceDigest"))
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("\n"));
            return expected.equals(AuthorityBackendDescriptorDigests.sha256Hex(canonical));
        }
    }
}
