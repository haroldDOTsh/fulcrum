package sh.harold.fulcrum.core.artifact;

import sh.harold.fulcrum.core.manifest.ArtifactPin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ArtifactSourceResolver {
    private final Path cacheRoot;
    private final ArtifactBytesResolver bytesResolver;
    private final ArtifactSignatureVerifier signatureVerifier;

    public ArtifactSourceResolver(
            Path cacheRoot,
            ArtifactBytesResolver bytesResolver,
            ArtifactSignatureVerifier signatureVerifier) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
        this.bytesResolver = Objects.requireNonNull(bytesResolver, "bytesResolver");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
    }

    public VerifiedArtifact resolve(ArtifactSourceRequest request) {
        ArtifactSourceRequest checked = Objects.requireNonNull(request, "request");
        List<ArtifactVerificationStep> steps = new ArrayList<>();
        if (checked.policy().productionMode() && !checked.sourceKind().productionEligible()) {
            throw refused(checked, Optional.empty(), Optional.empty(), Optional.empty(), steps,
                    "production artifact sources must be OCI");
        }

        try {
            ArtifactSourceBytes sourceBytes = bytesResolver.resolve(checked);
            steps.add(ArtifactVerificationStep.SOURCE_RESOLVED);
            byte[] bytes = sourceBytes.bytes();
            String actualDigest = sha256(bytes);
            ArtifactDigestReference digest = new ArtifactDigestReference("sha-256", actualDigest);
            verifyExpectedDigest(checked, sourceBytes, digest, steps);

            ArtifactPin pin = new ArtifactPin(checked.artifactId(), "sha256:" + actualDigest, checked.compatibility());
            steps.add(ArtifactVerificationStep.DIGEST_PINNED);
            Path cachedPath = ArtifactBlobLayout.cachePath(cacheRoot, pin);
            Files.createDirectories(cachedPath.getParent());
            if (Files.exists(cachedPath)) {
                steps.add(ArtifactVerificationStep.CACHE_HIT);
                String cachedDigest = sha256(Files.readAllBytes(cachedPath));
                if (!actualDigest.equals(cachedDigest)) {
                    Files.deleteIfExists(cachedPath);
                    throw refused(checked, Optional.of(pin), Optional.of(digest.wireValue()), Optional.of(cachedPath), steps,
                            "cached artifact digest mismatch");
                }
                steps.add(ArtifactVerificationStep.CACHE_REHASHED);
            } else {
                Path staged = Files.createTempFile(cachedPath.getParent(), cachedPath.getFileName().toString(), ".staged");
                Files.write(staged, bytes);
                Files.move(staged, cachedPath, StandardCopyOption.ATOMIC_MOVE);
                steps.add(ArtifactVerificationStep.CACHE_WRITTEN);
            }

            Optional<String> signatureEvidence = verifySignature(checked, digest, bytes, steps);
            ArtifactVerificationReceipt receipt = new ArtifactVerificationReceipt(
                    ArtifactVerificationStatus.VERIFIED,
                    Optional.of(pin),
                    checked.sourceKind(),
                    checked.reference(),
                    Optional.of(digest.wireValue()),
                    Optional.of(cachedPath),
                    steps,
                    signatureEvidence,
                    Optional.empty());
            return new VerifiedArtifact(pin, checked.sourceKind(), checked.reference(), cachedPath, receipt);
        } catch (IOException exception) {
            throw refused(checked, Optional.empty(), Optional.empty(), Optional.empty(), steps,
                    "artifact source resolution failed: " + exception.getMessage());
        }
    }

    private static void verifyExpectedDigest(
            ArtifactSourceRequest request,
            ArtifactSourceBytes sourceBytes,
            ArtifactDigestReference actualDigest,
            List<ArtifactVerificationStep> steps) {
        Optional<String> expected = request.expectedDigest().or(sourceBytes::resolvedDigest);
        if (expected.isEmpty()) {
            return;
        }
        ArtifactDigestReference expectedDigest = ArtifactDigestReference.parse(expected.orElseThrow());
        if (!expectedDigest.algorithm().equals("sha-256")) {
            throw refused(request, Optional.empty(), Optional.empty(), Optional.empty(), steps,
                    "artifact digest verification requires sha-256");
        }
        if (!expectedDigest.value().equals(actualDigest.value())) {
            throw refused(request, Optional.empty(), Optional.of(actualDigest.wireValue()), Optional.empty(), steps,
                    "artifact digest mismatch");
        }
    }

    private Optional<String> verifySignature(
            ArtifactSourceRequest request,
            ArtifactDigestReference digest,
            byte[] bytes,
            List<ArtifactVerificationStep> steps) throws IOException {
        boolean signatureRequired = request.sourceKind().productionEligible() || request.policy().requireSignature();
        if (signatureRequired || request.signatureReference().isPresent()) {
            if (request.signatureReference().isEmpty()) {
                throw refused(request, Optional.empty(), Optional.of(digest.wireValue()), Optional.empty(), steps,
                        "artifact signature missing");
            }
            ArtifactSignatureReceipt signatureReceipt = signatureVerifier.verify(request, digest, bytes.clone());
            if (!signatureReceipt.verified()) {
                throw refused(request, Optional.empty(), Optional.of(digest.wireValue()), Optional.empty(), steps,
                        "artifact signature verification failed: " + signatureReceipt.evidence());
            }
            steps.add(ArtifactVerificationStep.SIGNATURE_VERIFIED);
            return Optional.of(signatureReceipt.evidence());
        }
        if (request.policy().allowUnsignedLocalImport() && !request.sourceKind().productionEligible()) {
            steps.add(ArtifactVerificationStep.UNSIGNED_LOCAL_IMPORT_ACCEPTED);
            return Optional.of("unsigned local/import artifact accepted by explicit policy");
        }
        throw refused(request, Optional.empty(), Optional.of(digest.wireValue()), Optional.empty(), steps,
                "artifact signature missing");
    }

    private static ArtifactVerificationException refused(
            ArtifactSourceRequest request,
            Optional<ArtifactPin> pin,
            Optional<String> digest,
            Optional<Path> cachedPath,
            List<ArtifactVerificationStep> steps,
            String reason) {
        List<ArtifactVerificationStep> refusedSteps = new ArrayList<>(steps);
        refusedSteps.add(ArtifactVerificationStep.REFUSED);
        return new ArtifactVerificationException(new ArtifactVerificationReceipt(
                ArtifactVerificationStatus.REFUSED,
                pin,
                request.sourceKind(),
                request.reference(),
                digest,
                cachedPath,
                refusedSteps,
                Optional.empty(),
                Optional.of(reason)));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }
}
