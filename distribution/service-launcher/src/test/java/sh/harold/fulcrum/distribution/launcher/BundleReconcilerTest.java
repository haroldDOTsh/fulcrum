package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.fulcrum.adapters.objectstorage.LocalObjectStorageAdapter;
import sh.harold.fulcrum.api.kernel.ArtifactId;
import sh.harold.fulcrum.capability.api.CapabilityExtensionPoint;
import sh.harold.fulcrum.capability.api.CapabilityScope;
import sh.harold.fulcrum.capability.api.ContributionDeclaration;
import sh.harold.fulcrum.capability.bundle.ContributionBundleLoader;
import sh.harold.fulcrum.core.manifest.ArtifactPin;
import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BundleReconcilerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path tempDir;

    @Test
    void desiredStateRoundTripsDeclaredBundles() {
        BundleDesiredState state = BundleDesiredState.empty().addOrReplace(escrowBundle());

        BundleDesiredState decoded = BundleDesiredState.fromJson(state.toJson());

        assertEquals(BundleDesiredState.SCHEMA, decoded.schema());
        assertEquals(1, decoded.bundles().size());
        assertEquals(escrowBundle(), decoded.find("auction-escrow").orElseThrow());
    }

    @Test
    void reconcilerStartsVerifiedAuthorizedBundleAndIssuesGrant() {
        BundleReconciler reconciler = reconciler(bundle -> Optional.of(verification(bundle)));

        BundleReconcileReceipt receipt = reconciler.reconcile(
                BundleDesiredState.empty().addOrReplace(escrowBundle()),
                authorization()).getFirst();

        assertEquals("RUNNING", receipt.status());
        assertEquals("sha256:auction-escrow", receipt.digest());
        assertTrue(receipt.grantFingerprint().isPresent());
        assertEquals("ACTIVE", receipt.grantState().orElseThrow());
        assertTrue(receipt.grantEvidence().orElseThrow().contains("grant=ACTIVE"));
        assertTrue(receipt.artifactVerificationEvidence().orElseThrow().contains("verified=true"));
        assertTrue(receipt.instanceId().isPresent());
        assertTrue(receipt.manifestHash().isPresent());
        assertTrue(receipt.manifestPath().isPresent());
        assertTrue(receipt.launchNonce().isPresent());
        assertTrue(receipt.registrationReceiptId().isPresent());
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(escrowBundle(), authorization()).orElseThrow();
        assertTrue(grant.securityContext().credentialScope().permits(
                HostResourceFamily.AUTHORITY_DOMAIN,
                HostAccessMode.PRODUCE,
                "auction-escrow"));
        assertTrue(grant.securityContext().credentialScope().permits(
                HostResourceFamily.RESOURCE_CLASS,
                HostAccessMode.READ,
                "external-authority"));
    }

    @Test
    void reconcilerDoesNotAppendDuplicateGrantLifecycleForSatisfiedBackend() {
        BundleInstallGrantStateStore grantStore = new BundleInstallGrantStateStore(tempDir);
        BundleReconciler reconciler = new BundleReconciler(
                bundle -> Optional.of(verification(bundle)),
                new BundleInstallGrantIssuer(),
                grantStore,
                supervisor(),
                absentContributionSupervisor(),
                CLOCK);
        BundleDesiredState desiredState = BundleDesiredState.empty().addOrReplace(escrowBundle());

        BundleReconcileReceipt first = reconciler.reconcile(desiredState, authorization()).getFirst();
        BundleReconcileReceipt second = reconciler.reconcile(desiredState, authorization()).getFirst();

        assertEquals("RUNNING", first.status());
        assertEquals("RUNNING", second.status());
        assertEquals("ACTIVE", second.grantState().orElseThrow());
        assertEquals(1, grantStore.read().size());
    }

    @Test
    void reconcilerStagesContributionBundlesWithoutStartingBackendInstance() {
        DeclaredBundle bundle = contributionBundle(
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                descriptorDigest());
        boolean[] contributionInstalled = {false};
        BundleReconciler reconciler = new BundleReconciler(
                candidate -> Optional.of(verification(candidate)),
                new BundleInstallGrantIssuer(),
                new BundleInstallGrantStateStore(tempDir),
                refusingInstanceSupervisor(),
                new BundleContributionSupervisor() {
                    @Override
                    public BundleContributionInstallReceipt install(
                            DeclaredBundle bundle,
                            IssuedBundleGrant grant,
                            AuthorityArtifactVerificationEvidence artifactVerification,
                            Instant now) {
                        contributionInstalled[0] = true;
                        return BundleContributionInstallReceipt.staged(
                                "author-dev/cache/sample-contribution.artifact",
                                "loadStatus=VERIFIED|steps=PULLED,VERIFIED,MANIFEST_PARSED,MATERIALIZATION_MATCHED");
                    }

                    @Override
                    public BundleContributionRemovalReceipt remove(String bundleId, Instant now) {
                        return BundleContributionRemovalReceipt.removed("contribution-already-absent");
                    }
                },
                CLOCK);

        BundleReconcileReceipt receipt = reconciler.reconcile(
                BundleDesiredState.empty().addOrReplace(bundle),
                contributionAuthorization()).getFirst();

        assertEquals("STAGED", receipt.status());
        assertEquals("contribution-artifact-verified-and-staged", receipt.reason());
        assertTrue(receipt.satisfied());
        assertTrue(contributionInstalled[0]);
        assertEquals("ACTIVE", receipt.grantState().orElseThrow());
        assertTrue(receipt.contributionCachePath().isPresent());
        assertTrue(receipt.contributionEvidence().orElseThrow().contains("MATERIALIZATION_MATCHED"));
    }

    @Test
    void reconcilerDeniesBundleClaimingUngrantedAuthorityDomain() {
        BundleReconciler reconciler = reconciler(bundle -> Optional.of(verification(bundle)));
        BundleReconcileAuthorization authorization = new BundleReconcileAuthorization(
                Set.of("other.authority"),
                Set.of("external-authority"));

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
        assertEquals("instance-removed", receipt.reason());
        assertEquals("REVOKED", receipt.grantState().orElseThrow());
    }

    @Test
    void renderedSupervisorRecordsStartedRuntimeEvidenceWithoutClaimingRunning() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                startedRuntimeAdapter(),
                (rendered, manifest, launchNonce, now) -> BundleReadinessReceipt.pending("readiness-file-missing"),
                request -> {
                    throw new AssertionError("self-registration must come from the backend process");
                });

        BundleInstanceStartReceipt receipt = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("STARTED", receipt.status());
        assertTrue(receipt.started());
        assertTrue(receipt.runtimeEvidence().orElseThrow().contains("runtime=compose"));
        assertTrue(receipt.launchNonce().isPresent());
        assertTrue(receipt.registrationReceiptId().isEmpty());
        BundleInstanceRecord latest = stateStore.latestByBundle().get(bundle.id());
        assertEquals("STARTED", latest.status());
        assertEquals(receipt.runtimeEvidence(), latest.runtimeEvidence());
        assertEquals(receipt.launchNonce(), latest.launchNonce());
    }

    @Test
    void renderedSupervisorRendersClusterBackendAsHelmManagedDeployment() throws Exception {
        DeclaredBundle bundle = escrowBundle(DeploymentProfile.SMALL_PRODUCTION.id(), Optional.empty());
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                refusingRuntimeAdapter(),
                (rendered, manifest, launchNonce, now) -> {
                    throw new AssertionError("cluster render-only path must not poll local readiness");
                },
                request -> {
                    throw new AssertionError("cluster render-only path must not self-register in-process");
                });

        BundleInstanceStartReceipt receipt = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("RENDERED", receipt.status());
        assertTrue(receipt.rendered());
        BundleInstanceRecord latest = stateStore.latestByBundle().get(bundle.id());
        assertEquals("RENDERED", latest.status());
        Path renderedDir = tempDir.resolve("bundle-instances").resolve("instance-auction-escrow");
        String deployment = Files.readString(renderedDir.resolve("helm").resolve("templates").resolve("backend-deployment.yaml"));
        assertTrue(deployment.contains("kind: Deployment"));
        assertTrue(deployment.contains("app.kubernetes.io/managed-by: \"Helm\""));
        assertTrue(deployment.contains("meta.helm.sh/release-name"));
        assertTrue(deployment.contains("sh.harold.fulcrum/profile: \"small-production\""));
        assertTrue(deployment.contains("ghcr.io/harolddotsh/auction-escrow-backend@"));
        assertTrue(deployment.contains("FULCRUM_AUTHORITY_REGISTRATION_ENDPOINT"));
        assertTrue(deployment.contains("http://fulcrum-controller-service:18085/authority-backends/register"));
        assertTrue(Files.readString(renderedDir.resolve("checksums.txt"))
                .contains("helm/templates/backend-deployment.yaml="));
    }

    @Test
    void renderedSupervisorPromotesStartedInstanceOnlyAfterReadinessEvidenceMatches() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                startedRuntimeAdapter(),
                (rendered, manifest, launchNonce, now) -> BundleReadinessReceipt.ready(
                        "registration-" + manifest.instanceId(),
                        "readyFile=" + rendered.workDir().resolve("runtime").resolve("backend.ready")
                                + "|receiptId=registration-" + manifest.instanceId()
                                + "|bootNonce=" + launchNonce
                                + "|evidenceDigest=ready"),
                request -> {
                    throw new AssertionError("self-registration must come from the backend process");
                });

        BundleInstanceStartReceipt receipt = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("RUNNING", receipt.status());
        assertTrue(receipt.running());
        assertEquals("registration-instance-auction-escrow", receipt.registrationReceiptId().orElseThrow());
        BundleInstanceRecord latest = stateStore.latestByBundle().get(bundle.id());
        assertEquals("RUNNING", latest.status());
        assertEquals(receipt.registrationEvidence(), latest.registrationEvidence());
    }

    @Test
    void removalStopsStartedInstanceWithoutRegistrationReceipt() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        boolean[] stopped = {false};
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                startedRuntimeAdapter(stopped),
                (rendered, manifest, launchNonce, now) -> BundleReadinessReceipt.pending("readiness-file-missing"),
                request -> {
                    throw new AssertionError("started-but-unregistered removal must not deregister");
                });
        supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        BundleInstanceRemovalReceipt removal = supervisor.remove(bundle.id(), CLOCK.instant());

        assertEquals("REMOVED", removal.status());
        assertEquals("runtime-stopped-grant-revoked", removal.reason());
        assertTrue(stopped[0]);
        assertEquals("REMOVED", stateStore.latestByBundle().get(bundle.id()).status());
    }

    @Test
    void matchingRunningRecordDoesNotStartRuntimeAgain() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        int[] starts = {0};
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                countingStartedRuntimeAdapter(starts),
                (rendered, manifest, launchNonce, now) -> BundleReadinessReceipt.ready(
                        "registration-" + manifest.instanceId(),
                        "readyFile=" + rendered.workDir().resolve("runtime").resolve("backend.ready")
                                + "|receiptId=registration-" + manifest.instanceId()
                                + "|bootNonce=" + launchNonce
                                + "|evidenceDigest=ready"),
                request -> {
                    throw new AssertionError("self-registration must come from the backend process");
                });

        assertEquals("RUNNING", supervisor.start(bundle, grant, verification(bundle), CLOCK.instant()).status());
        BundleInstanceStartReceipt second = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("RUNNING", second.status());
        assertEquals("instance-observed-running-and-self-registered", second.reason());
        assertEquals(stateStore.latestByBundle().get(bundle.id()).launchNonce(), second.launchNonce());
        assertEquals(1, starts[0]);
    }

    @Test
    void matchingRunningRecordFailsClosedWhenRuntimeNoLongerRuns() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        int[] starts = {0};
        int[] readinessObservations = {0};
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                new BundleRuntimeAdapter() {
                    @Override
                    public BundleRuntimeStartReceipt start(
                            BundleRenderedInstance rendered,
                            BundleInstanceManifest manifest,
                            Instant now) {
                        starts[0]++;
                        return BundleRuntimeStartReceipt.started(
                                "runtime=compose|exitCode=0|manifestHash=" + rendered.manifestHash());
                    }

                    @Override
                    public BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now) {
                        return BundleRuntimeStopReceipt.stopped(
                                "runtime=compose|exitCode=0|manifestHash=" + record.manifestHash().orElse("none"));
                    }

                    @Override
                    public BundleRuntimeObservation observe(BundleInstanceRecord record, Instant now) {
                        return BundleRuntimeObservation.stopped(
                                "runtime=compose|status=stopped|manifestHash="
                                        + record.manifestHash().orElse("none"));
                    }
                },
                (rendered, manifest, launchNonce, now) -> {
                    readinessObservations[0]++;
                    return BundleReadinessReceipt.ready(
                            "registration-" + manifest.instanceId(),
                            "readyFile=" + rendered.workDir().resolve("runtime").resolve("backend.ready")
                                    + "|receiptId=registration-" + manifest.instanceId()
                                    + "|bootNonce=" + launchNonce
                                    + "|evidenceDigest=ready");
                },
                request -> {
                    throw new AssertionError("self-registration must come from the backend process");
                });

        assertEquals("RUNNING", supervisor.start(bundle, grant, verification(bundle), CLOCK.instant()).status());
        BundleInstanceStartReceipt second = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("START_FAILED", second.status());
        assertEquals("runtime-process-not-running", second.reason());
        assertEquals(1, starts[0]);
        assertEquals(1, readinessObservations[0]);
        assertEquals("START_FAILED", stateStore.latestByBundle().get(bundle.id()).status());
    }

    @Test
    void matchingStartedRecordObservesReadinessWithoutStartingRuntimeAgain() {
        DeclaredBundle bundle = escrowBundle();
        IssuedBundleGrant grant = new BundleInstallGrantIssuer().issue(bundle, authorization()).orElseThrow();
        BundleInstanceStateStore stateStore = new BundleInstanceStateStore(tempDir);
        int[] starts = {0};
        int[] observations = {0};
        RenderedBundleInstanceSupervisor supervisor = new RenderedBundleInstanceSupervisor(
                stateStore,
                new BundleInstanceArtifactRenderer(tempDir),
                countingStartedRuntimeAdapter(starts),
                (rendered, manifest, launchNonce, now) -> {
                    observations[0]++;
                    if (observations[0] == 1) {
                        return BundleReadinessReceipt.pending("readiness-file-missing");
                    }
                    return BundleReadinessReceipt.ready(
                            "registration-" + manifest.instanceId(),
                            "readyFile=" + rendered.workDir().resolve("runtime").resolve("backend.ready")
                                    + "|receiptId=registration-" + manifest.instanceId()
                                    + "|bootNonce=" + launchNonce
                                    + "|evidenceDigest=ready");
                },
                request -> {
                    throw new AssertionError("self-registration must come from the backend process");
                });

        assertEquals("STARTED", supervisor.start(bundle, grant, verification(bundle), CLOCK.instant()).status());
        BundleInstanceStartReceipt second = supervisor.start(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("RUNNING", second.status());
        assertEquals("instance-observed-self-registered", second.reason());
        assertEquals(1, starts[0]);
        assertEquals(2, observations[0]);
    }

    @Test
    void contributionSupervisorStagesVerifiedAuthorDevArtifact() throws Exception {
        String descriptorDigest = descriptorDigest();
        byte[] jarBytes = contributionJar("sample-contribution", descriptorDigest);
        String bundleDigest = "sha256:" + sha256(jarBytes);
        DeclaredBundle bundle = contributionBundle(bundleDigest, descriptorDigest);
        LocalObjectStorageAdapter objectStorage = new LocalObjectStorageAdapter(
                tempDir.resolve("author-dev").resolve("objects"),
                "artifact-store");
        objectStorage.put(
                new ArtifactPin(new ArtifactId("artifact.bundle." + bundle.id()), bundle.digest(), "fulcrum-bundle-v1"),
                jarBytes);
        IssuedBundleGrant grant = new BundleInstallGrantIssuer()
                .issue(bundle, contributionAuthorization())
                .orElseThrow();
        BundleContributionStateStore stateStore = new BundleContributionStateStore(tempDir);
        BundleContributionRuntimeSupervisor supervisor = new BundleContributionRuntimeSupervisor(
                stateStore,
                new ContributionBundleLoader(
                        "artifact-store",
                        tempDir.resolve("author-dev").resolve("cache"),
                        objectStorage::read));

        BundleContributionInstallReceipt receipt = supervisor.install(bundle, grant, verification(bundle), CLOCK.instant());
        BundleContributionInstallReceipt second = supervisor.install(bundle, grant, verification(bundle), CLOCK.instant());

        assertEquals("STAGED", receipt.status());
        assertTrue(receipt.cachePath().isPresent());
        assertTrue(receipt.loadEvidence().orElseThrow().contains("MATERIALIZATION_MATCHED"));
        assertEquals("STAGED", second.status());
        assertEquals("contribution-already-staged", second.reason());
        assertEquals(receipt.cachePath(), second.cachePath());
        assertEquals(1, stateStore.read().size());
        BundleContributionRecord latest = stateStore.latestByBundle().get(bundle.id());
        assertEquals("STAGED", latest.status());
        assertEquals(bundle.digest(), latest.digest());
        assertTrue(latest.cachePath().isPresent());
    }

    private BundleReconciler reconciler(BundleArtifactVerificationPort verifier) {
        return new BundleReconciler(
                verifier,
                new BundleInstallGrantIssuer(),
                new BundleInstallGrantStateStore(tempDir),
                supervisor(),
                absentContributionSupervisor(),
                CLOCK);
    }

    private static BundleInstanceSupervisor supervisor() {
        return new BundleInstanceSupervisor() {
            @Override
            public BundleInstanceStartReceipt start(
                    DeclaredBundle bundle,
                    IssuedBundleGrant grant,
                    AuthorityArtifactVerificationEvidence artifactVerification,
                    Instant now) {
                return BundleInstanceStartReceipt.running(
                        "instance-" + bundle.id(),
                        "shape-" + bundle.id(),
                        "manifest-" + bundle.id(),
                        "bundle-instances/instance-" + bundle.id() + "/manifest.json",
                        "launch-" + bundle.id(),
                        "runtime-evidence-" + bundle.id(),
                        "registration-" + bundle.id(),
                        "registration-evidence-" + bundle.id());
            }

            @Override
            public BundleInstanceRemovalReceipt remove(String bundleId, Instant now) {
                return BundleInstanceRemovalReceipt.removed(
                        Optional.of("instance-" + bundleId),
                        Optional.of("registration-" + bundleId),
                        "instance-removed");
            }
        };
    }

    private static BundleInstanceSupervisor refusingInstanceSupervisor() {
        return new BundleInstanceSupervisor() {
            @Override
            public BundleInstanceStartReceipt start(
                    DeclaredBundle bundle,
                    IssuedBundleGrant grant,
                    AuthorityArtifactVerificationEvidence artifactVerification,
                    Instant now) {
                throw new AssertionError("contribution bundles must not start backend instances");
            }

            @Override
            public BundleInstanceRemovalReceipt remove(String bundleId, Instant now) {
                return BundleInstanceRemovalReceipt.removed(
                        Optional.empty(),
                        Optional.empty(),
                        "instance-already-absent");
            }
        };
    }

    private static BundleContributionSupervisor absentContributionSupervisor() {
        return new BundleContributionSupervisor() {
            @Override
            public BundleContributionInstallReceipt install(
                    DeclaredBundle bundle,
                    IssuedBundleGrant grant,
                    AuthorityArtifactVerificationEvidence artifactVerification,
                    Instant now) {
                throw new AssertionError("authority backend bundles must not stage contributions");
            }

            @Override
            public BundleContributionRemovalReceipt remove(String bundleId, Instant now) {
                return BundleContributionRemovalReceipt.removed("contribution-already-absent");
            }
        };
    }

    private static BundleRuntimeAdapter startedRuntimeAdapter() {
        return startedRuntimeAdapter(new boolean[1]);
    }

    private static BundleRuntimeAdapter startedRuntimeAdapter(boolean[] stopped) {
        return countingStartedRuntimeAdapter(new int[1], stopped);
    }

    private static BundleRuntimeAdapter countingStartedRuntimeAdapter(int[] starts) {
        return countingStartedRuntimeAdapter(starts, new boolean[1]);
    }

    private static BundleRuntimeAdapter countingStartedRuntimeAdapter(int[] starts, boolean[] stopped) {
        return new BundleRuntimeAdapter() {
            @Override
            public BundleRuntimeStartReceipt start(
                    BundleRenderedInstance rendered,
                    BundleInstanceManifest manifest,
                    Instant now) {
                starts[0]++;
                return BundleRuntimeStartReceipt.started(
                        "runtime=compose|exitCode=0|manifestHash=" + rendered.manifestHash());
            }

            @Override
            public BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now) {
                stopped[0] = true;
                return BundleRuntimeStopReceipt.stopped(
                        "runtime=compose|exitCode=0|manifestHash=" + record.manifestHash().orElse("none"));
            }

            @Override
            public BundleRuntimeObservation observe(BundleInstanceRecord record, Instant now) {
                return BundleRuntimeObservation.processRunning(
                        record,
                        "runtime=compose|status=running|manifestHash=" + record.manifestHash().orElse("none"));
            }
        };
    }

    private static BundleRuntimeAdapter refusingRuntimeAdapter() {
        return new BundleRuntimeAdapter() {
            @Override
            public BundleRuntimeStartReceipt start(
                    BundleRenderedInstance rendered,
                    BundleInstanceManifest manifest,
                    Instant now) {
                throw new AssertionError("runtime adapter must not be invoked");
            }

            @Override
            public BundleRuntimeStopReceipt stop(BundleInstanceRecord record, Instant now) {
                throw new AssertionError("runtime adapter must not be invoked");
            }
        };
    }

    private static BundleReconcileAuthorization authorization() {
        return new BundleReconcileAuthorization(
                Set.of("auction-escrow"),
                Set.of("external-authority"));
    }

    private static BundleReconcileAuthorization contributionAuthorization() {
        return new BundleReconcileAuthorization(
                Set.of("sample.contribution"),
                Set.of("sample-contribution-host"));
    }

    private static DeclaredBundle escrowBundle() {
        return escrowBundle(DeploymentProfile.SINGLE_MACHINE.id(), Optional.of("full-engine"));
    }

    private static DeclaredBundle escrowBundle(String placementProfile, Optional<String> placementTier) {
        return new DeclaredBundle(
                "auction-escrow",
                "oci://ghcr.io/harolddotsh/auction-escrow@sha256:auction-escrow",
                "sha256:auction-escrow",
                "authority-backend",
                "network",
                placementProfile,
                placementTier,
                Optional.of("ghcr.io/harolddotsh/auction-escrow-backend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                Optional.of("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                List.of("auction-escrow"),
                List.of("external-authority"),
                Optional.empty(),
                List.of(),
                true);
    }

    private static DeclaredBundle contributionBundle(String digest, String descriptorDigest) {
        return new DeclaredBundle(
                "sample-contribution",
                "oci://ghcr.io/harolddotsh/sample-contribution@" + digest,
                digest,
                "contribution",
                "network",
                "single-machine",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of("sample.contribution"),
                List.of("sample-contribution-host"),
                Optional.of(descriptorDigest),
                List.of(new ContributionDeclaration(
                        CapabilityExtensionPoint.PAPER_CHAT_PIPELINE,
                        CapabilityScope.NETWORK,
                        10)),
                true);
    }

    private static AuthorityArtifactVerificationEvidence verification(DeclaredBundle bundle) {
        return AuthorityArtifactVerificationEvidence.verified(
                "OCI",
                bundle.artifactRef(),
                bundle.digest(),
                "cosign:test");
    }

    private static byte[] contributionJar(String bundleId, String descriptorDigest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("META-INF/fulcrum/bundle.properties"));
            jar.write(("""
                    bundle.id=%s
                    descriptor.digest=%s
                    bundle.digest=declared-by-artifact-pin
                    providers=example.Provider
                    contributions=Paper.ChatPipeline:network:10
                    """).formatted(bundleId, descriptorDigest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String descriptorDigest() {
        return "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }
}
