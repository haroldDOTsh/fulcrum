package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BundleReconcilerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void desiredStateRoundTripsDeclaredBundles() {
        BundleDesiredState state = BundleDesiredState.empty().addOrReplace(escrowBundle());

        BundleDesiredState decoded = BundleDesiredState.fromJson(state.toJson());

        assertEquals(BundleDesiredState.SCHEMA, decoded.schema());
        assertEquals(1, decoded.bundles().size());
        assertEquals(escrowBundle(), decoded.find("auction-escrow").orElseThrow());
    }

    @Test
    void reconcilerInstallsVerifiedAuthorizedBundleAndIssuesGrant() {
        BundleReconciler reconciler = reconciler(bundle -> Optional.of(verification(bundle)));

        BundleReconcileReceipt receipt = reconciler.reconcile(
                BundleDesiredState.empty().addOrReplace(escrowBundle()),
                authorization()).getFirst();

        assertEquals("INSTALLED", receipt.status());
        assertEquals("sha256:auction-escrow", receipt.digest());
        assertTrue(receipt.grantFingerprint().isPresent());
        assertTrue(receipt.artifactVerificationEvidence().orElseThrow().contains("verified=true"));
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(escrowBundle(), authorization()).orElseThrow();
        assertTrue(grant.securityContext().credentialScope().permits(
                HostResourceFamily.AUTHORITY_DOMAIN,
                HostAccessMode.PRODUCE,
                "auction.escrow"));
        assertTrue(grant.securityContext().credentialScope().permits(
                HostResourceFamily.RESOURCE_CLASS,
                HostAccessMode.READ,
                "auction-escrow-backend"));
    }

    @Test
    void reconcilerDeniesBundleClaimingUngrantedAuthorityDomain() {
        BundleReconciler reconciler = reconciler(bundle -> Optional.of(verification(bundle)));
        BundleReconcileAuthorization authorization = new BundleReconcileAuthorization(
                Set.of("other.authority"),
                Set.of("auction-escrow-backend"));

        BundleReconcileReceipt receipt = reconciler.reconcile(
                BundleDesiredState.empty().addOrReplace(escrowBundle()),
                authorization).getFirst();

        assertEquals("DENIED", receipt.status());
        assertEquals("GRANT_NOT_AUTHORIZED", receipt.reason());
        assertTrue(receipt.grantFingerprint().isEmpty());
    }

    @Test
    void reconcilerFailsClosedWithoutArtifactVerification() {
        BundleReconciler reconciler = reconciler(bundle -> Optional.empty());

        BundleReconcileReceipt receipt = reconciler.reconcile(
                BundleDesiredState.empty().addOrReplace(escrowBundle()),
                authorization()).getFirst();

        assertEquals("DENIED", receipt.status());
        assertEquals("ARTIFACT_VERIFICATION_FAILED", receipt.reason());
    }

    @Test
    void removalUndeclaresBundleAndEmitsRevocationReceipt() {
        BundleDesiredState state = BundleDesiredState.empty().addOrReplace(escrowBundle());
        BundleDesiredState removed = state.remove("auction-escrow");

        BundleReconcileReceipt receipt = reconciler(bundle -> Optional.of(verification(bundle)))
                .reconcileRemoval("auction-escrow");

        assertTrue(removed.find("auction-escrow").isEmpty());
        assertEquals("REMOVED", receipt.status());
        assertEquals("declaration-removed-grant-revoked", receipt.reason());
    }

    private static BundleReconciler reconciler(BundleArtifactVerificationPort verifier) {
        return new BundleReconciler(verifier, new BundleInstallGrantIssuer(), CLOCK);
    }

    private static BundleReconcileAuthorization authorization() {
        return new BundleReconcileAuthorization(
                Set.of("auction.escrow"),
                Set.of("auction-escrow-backend"));
    }

    private static DeclaredBundle escrowBundle() {
        return new DeclaredBundle(
                "auction-escrow",
                "oci://ghcr.io/sh-harold/auction-escrow@sha256:auction-escrow",
                "sha256:auction-escrow",
                "authority-backend",
                "network",
                "single-machine",
                Optional.of("full-engine"),
                List.of("auction.escrow"),
                List.of("auction-escrow-backend"),
                true);
    }

    private static AuthorityArtifactVerificationEvidence verification(DeclaredBundle bundle) {
        return AuthorityArtifactVerificationEvidence.verified(
                "OCI",
                bundle.artifactRef(),
                bundle.digest(),
                "cosign:test");
    }
}
