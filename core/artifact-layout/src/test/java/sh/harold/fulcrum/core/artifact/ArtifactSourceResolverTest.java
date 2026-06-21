package sh.harold.fulcrum.core.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.api.kernel.ArtifactId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ArtifactSourceResolverTest {
    @Test
    void localFileImportNormalizesToDigestPinnedCacheWhenDevPolicyAllowsUnsigned(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "local contribution bundle".getBytes(StandardCharsets.UTF_8);
        ArtifactSourceResolver resolver = resolver(tempDir, bytes, ArtifactSignatureReceipt.refused("not called"));
        ArtifactSourceRequest request = request(
                ArtifactSourceKind.LOCAL_FILE,
                "file://bundle.jar",
                Optional.empty(),
                Optional.empty(),
                ArtifactSourcePolicy.localDevelopment());

        VerifiedArtifact artifact = resolver.resolve(request);

        assertEquals("sha256:" + sha256(bytes), artifact.artifactPin().digest());
        assertEquals(ArtifactSourceKind.LOCAL_FILE, artifact.sourceKind());
        assertTrue(Files.exists(artifact.cachedPath()));
        assertEquals(bytes.length, Files.readAllBytes(artifact.cachedPath()).length);
        assertEquals(ArtifactVerificationStatus.VERIFIED, artifact.verificationReceipt().status());
        assertTrue(artifact.verificationReceipt().steps().contains(ArtifactVerificationStep.DIGEST_PINNED));
        assertTrue(artifact.verificationReceipt().steps().contains(ArtifactVerificationStep.CACHE_WRITTEN));
        assertTrue(artifact.verificationReceipt().steps().contains(ArtifactVerificationStep.UNSIGNED_LOCAL_IMPORT_ACCEPTED));
    }

    @Test
    void productionPolicyRejectsLocalFileBeforeImport(@TempDir Path tempDir) {
        ArtifactSourceResolver resolver = resolver(
                tempDir,
                "bytes".getBytes(StandardCharsets.UTF_8),
                ArtifactSignatureReceipt.refused("not called"));
        ArtifactSourceRequest request = request(
                ArtifactSourceKind.LOCAL_FILE,
                "file://bundle.jar",
                Optional.empty(),
                Optional.empty(),
                ArtifactSourcePolicy.production());

        ArtifactVerificationException exception = assertThrows(ArtifactVerificationException.class, () -> resolver.resolve(request));

        assertEquals(ArtifactVerificationStatus.REFUSED, exception.receipt().status());
        assertEquals(Optional.of("production artifact sources must be OCI"), exception.receipt().refusalReason());
        assertTrue(exception.receipt().steps().contains(ArtifactVerificationStep.REFUSED));
    }

    @Test
    void ociReferenceRequiresSignatureAndDigestMatch(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "oci bundle layer".getBytes(StandardCharsets.UTF_8);
        ArtifactSourceResolver resolver = resolver(tempDir, bytes, ArtifactSignatureReceipt.verified("cosign:identity=fulcrum-release"));
        ArtifactSourceRequest request = request(
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/harolddotsh/auction@sha256:" + sha256(bytes),
                Optional.of("sha256:" + sha256(bytes)),
                Optional.of("cosign://ghcr.io/harolddotsh/auction"),
                ArtifactSourcePolicy.production());

        VerifiedArtifact artifact = resolver.resolve(request);

        assertEquals(ArtifactVerificationStatus.VERIFIED, artifact.verificationReceipt().status());
        assertEquals(Optional.of("cosign:identity=fulcrum-release"), artifact.verificationReceipt().signatureEvidence());
        assertTrue(artifact.verificationReceipt().steps().contains(ArtifactVerificationStep.SIGNATURE_VERIFIED));
        assertTrue(artifact.verificationReceipt().steps().contains(ArtifactVerificationStep.CACHE_WRITTEN));
    }

    @Test
    void digestMismatchFailsClosed(@TempDir Path tempDir) {
        byte[] bytes = "oci bundle layer".getBytes(StandardCharsets.UTF_8);
        ArtifactSourceResolver resolver = resolver(tempDir, bytes, ArtifactSignatureReceipt.verified("cosign"));
        ArtifactSourceRequest request = request(
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/harolddotsh/auction:latest",
                Optional.of("sha256:" + "0".repeat(64)),
                Optional.of("cosign://ghcr.io/harolddotsh/auction"),
                ArtifactSourcePolicy.production());

        ArtifactVerificationException exception = assertThrows(ArtifactVerificationException.class, () -> resolver.resolve(request));

        assertEquals(Optional.of("artifact digest mismatch"), exception.receipt().refusalReason());
        assertTrue(exception.receipt().steps().contains(ArtifactVerificationStep.REFUSED));
    }

    @Test
    void poisonedCacheIsDeletedAndRefused(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "bundle bytes".getBytes(StandardCharsets.UTF_8);
        ArtifactSourceResolver resolver = resolver(tempDir, bytes, ArtifactSignatureReceipt.verified("cosign"));
        ArtifactSourceRequest request = request(
                ArtifactSourceKind.OCI,
                "oci://ghcr.io/harolddotsh/auction@sha256:" + sha256(bytes),
                Optional.of("sha256:" + sha256(bytes)),
                Optional.of("cosign://ghcr.io/harolddotsh/auction"),
                ArtifactSourcePolicy.production());
        VerifiedArtifact artifact = resolver.resolve(request);
        Files.writeString(artifact.cachedPath(), "poisoned", StandardCharsets.UTF_8);

        ArtifactVerificationException exception = assertThrows(ArtifactVerificationException.class, () -> resolver.resolve(request));

        assertEquals(Optional.of("cached artifact digest mismatch"), exception.receipt().refusalReason());
        assertFalse(Files.exists(artifact.cachedPath()));
    }

    private static ArtifactSourceResolver resolver(
            Path cacheRoot,
            byte[] bytes,
            ArtifactSignatureReceipt signatureReceipt) {
        return new ArtifactSourceResolver(
                cacheRoot,
                request -> new ArtifactSourceBytes(bytes, request.expectedDigest(), "test-bytes"),
                (request, digest, actualBytes) -> signatureReceipt);
    }

    private static ArtifactSourceRequest request(
            ArtifactSourceKind kind,
            String reference,
            Optional<String> expectedDigest,
            Optional<String> signatureReference,
            ArtifactSourcePolicy policy) {
        return new ArtifactSourceRequest(
                new ArtifactId("artifact.auction.bundle"),
                "fulcrum-bundle-v1",
                kind,
                reference,
                expectedDigest,
                signatureReference,
                policy);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
