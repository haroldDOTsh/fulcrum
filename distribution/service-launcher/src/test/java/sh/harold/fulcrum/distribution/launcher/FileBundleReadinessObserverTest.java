package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileBundleReadinessObserverTest {
    private static final Instant NOW = Instant.parse("2026-06-22T12:00:00Z");
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String IMAGE_DIGEST = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String LAUNCH_NONCE = "launch-nonce-sample";

    @TempDir
    private Path tempDir;

    @Test
    void acceptsMatchingReadyEvidence() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("runtime").resolve("backend.ready"), readinessDocument(DIGEST));

        BundleReadinessReceipt receipt = observer().observe(rendered(), manifest(), LAUNCH_NONCE, NOW);

        assertEquals("READY", receipt.status());
        assertEquals("registration-instance-sample", receipt.registrationReceiptId().orElseThrow());
        assertTrue(receipt.registrationEvidence().orElseThrow().contains("registrationSignature=signature-sample"));
        assertTrue(receipt.registrationEvidence().orElseThrow().contains("bootNonceDigest="));
    }

    @Test
    void rejectsMismatchedBundleDigestEvidence() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("runtime").resolve("backend.ready"), readinessDocument(
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));

        BundleReadinessReceipt receipt = observer().observe(rendered(), manifest(), LAUNCH_NONCE, NOW);

        assertEquals("REJECTED", receipt.status());
        assertEquals("readiness-bundle-digest-mismatch", receipt.reason());
    }

    @Test
    void rejectsReadyEvidenceFromPreviousLaunchNonce() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("runtime").resolve("backend.ready"), readinessDocument(
                DIGEST,
                "stale-launch-nonce"));

        BundleReadinessReceipt receipt = observer().observe(rendered(), manifest(), LAUNCH_NONCE, NOW);

        assertEquals("REJECTED", receipt.status());
        assertEquals("readiness-launch-nonce-mismatch", receipt.reason());
    }

    @Test
    void rejectsReadyEvidenceWithoutLaunchNonce() throws Exception {
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("runtime").resolve("backend.ready"), readinessDocument(
                DIGEST,
                null));

        BundleReadinessReceipt receipt = observer().observe(rendered(), manifest(), LAUNCH_NONCE, NOW);

        assertEquals("REJECTED", receipt.status());
        assertEquals("readiness-launch-nonce-missing", receipt.reason());
    }

    @Test
    void returnsPendingWhenReadyFileIsMissing() {
        BundleReadinessReceipt receipt = observer().observe(rendered(), manifest(), LAUNCH_NONCE, NOW);

        assertEquals("PENDING", receipt.status());
        assertEquals("readiness-file-missing", receipt.reason());
    }

    private static FileBundleReadinessObserver observer() {
        return new FileBundleReadinessObserver(Duration.ZERO, Duration.ofMillis(1));
    }

    private BundleRenderedInstance rendered() {
        return new BundleRenderedInstance(
                tempDir,
                tempDir.resolve("manifest.json"),
                tempDir.resolve("env").resolve("bootstrap.env"),
                tempDir.resolve("compose.yaml"),
                tempDir.resolve("helm"),
                tempDir.resolve("helm").resolve("templates").resolve("backend-deployment.yaml"),
                tempDir.resolve("checksums.txt"),
                "manifest-hash");
    }

    private static BundleInstanceManifest manifest() {
        return new BundleInstanceManifest(
                BundleInstanceManifest.SCHEMA,
                "sample-authority",
                "oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                DIGEST,
                "ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                IMAGE_DIGEST,
                "authority-backend",
                "network",
                "single-machine",
                Optional.of("full-engine"),
                List.of("sample.authority"),
                List.of("sample-backend"),
                "instance-sample",
                "authority-backend",
                "pool-network",
                "machine-single-machine",
                "principal-sample",
                "install://bundle/sample-authority/credential",
                "grant-fingerprint",
                "verified=true|sourceKind=OCI|digest=" + DIGEST,
                BundleInstanceManifest.SINGLE_MACHINE_REGISTRATION_ENDPOINT);
    }

    private static String readinessDocument(String bundleDigest) {
        return readinessDocument(bundleDigest, LAUNCH_NONCE);
    }

    private static String readinessDocument(String bundleDigest, String launchNonce) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("schema", "sample-readiness/v1");
        fields.put("status", "ready");
        fields.put("receiptId", "registration-instance-sample");
        fields.put("registrationSignature", "signature-sample");
        fields.put("bundleDigest", bundleDigest);
        fields.put("grantFingerprint", "grant-fingerprint");
        fields.put("principalId", "principal-sample");
        if (launchNonce != null) {
            fields.put("bootNonce", launchNonce);
        }
        fields.put("generatedAt", NOW.toString());
        String canonical = canonical(fields);
        return canonical
                + System.lineSeparator()
                + "evidenceDigest=" + AuthorityBackendDescriptorDigests.sha256Hex(canonical)
                + System.lineSeparator();
    }

    private static String canonical(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }
}
