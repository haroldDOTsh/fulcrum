package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperatorBundleCliTest {
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String IMAGE_DIGEST = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String DESCRIPTOR_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @TempDir
    private Path tempDir;

    @Test
    void bundleAddWritesDesiredStateReconcilesAndRendersLaunchArtifacts() throws Exception {
        Path stateDir = tempDir.resolve("install-state");

        LaunchResult add = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());
        assertTrue(add.out().contains("status=RENDERED"));
        assertTrue(add.out().contains("reason=instance-rendered-start-pending"));
        assertTrue(add.out().contains("grantFingerprint="));
        assertFalse(add.out().contains("grantFingerprint=none"));
        assertTrue(add.out().contains("grantState=ISSUED"));
        assertTrue(Files.readString(stateDir.resolve(BundleInstallGrantStateStore.FILE_NAME))
                .contains("\"status\":\"ISSUED\""));
        assertTrue(add.out().contains("instanceId=instance-sample-authority"));
        assertTrue(add.out().contains("manifestHash="));
        assertFalse(add.out().contains("manifestHash=none"));
        assertTrue(add.out().contains("manifestPath="));
        assertTrue(add.out().contains("launchNonce="));
        assertFalse(add.out().contains("launchNonce=none"));
        assertTrue(add.out().contains("runtimeEvidence=none"));
        assertTrue(add.out().contains("registrationReceiptId=none"));
        assertTrue(Files.readString(stateDir.resolve(BundleDesiredStateStore.FILE_NAME)).contains("sample-authority"));
        assertTrue(Files.readString(stateDir.resolve(BundleArtifactVerificationStore.FILE_NAME)).contains("cosign:test"));
        assertTrue(Files.readString(stateDir.resolve(BundleReceiptStore.FILE_NAME)).contains("\"status\":\"RENDERED\""));
        Path renderedDir = stateDir.resolve("bundle-instances").resolve("instance-sample-authority");
        assertTrue(Files.readString(renderedDir.resolve("manifest.json")).contains("\"schema\":\"fulcrum.bundle-instance-manifest/v1\""));
        assertTrue(Files.readString(renderedDir.resolve("compose.yaml")).contains("image: \"ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST + "\""));
        String env = Files.readString(renderedDir.resolve("env").resolve("bootstrap.env"));
        assertTrue(env.contains("FULCRUM_MANIFEST_HASH="));
        assertTrue(env.contains("FULCRUM_LAUNCH_NONCE="));
        assertTrue(env.contains("FULCRUM_BOOT_NONCE="));
        assertTrue(env.contains("FULCRUM_REGISTRATION_ENDPOINT=http://controller-service:18085/authority-backends/register"));
        assertTrue(env.contains("FULCRUM_READY_FILE=/var/run/fulcrum/backend.ready"));
        assertTrue(env.contains("FULCRUM_STARTUP_MODE=serve"));
        assertTrue(Files.readString(renderedDir.resolve("compose.yaml")).contains("./runtime:/var/run/fulcrum"));
        assertTrue(Files.isDirectory(renderedDir.resolve("runtime")));
        assertTrue(Files.readString(renderedDir.resolve("checksums.txt")).contains("compose.yaml="));

        LaunchResult list = run("bundle", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, list.code(), list.err());
        assertTrue(list.out().contains("bundles=1"));
        assertTrue(list.out().contains("bundle=sample-authority"));
        assertTrue(list.out().contains("runtimeStatus=RENDERED"));
        assertTrue(list.out().contains("instance=instance-sample-authority"));

        LaunchResult status = run("bundle", "status", "sample-authority", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("desired=DECLARED"));
        assertTrue(status.out().contains("runtimeStatus=RENDERED"));
        assertTrue(status.out().contains("grantStatus=ISSUED"));
        assertTrue(status.out().contains("launchNonce="));
        assertFalse(status.out().contains("launchNonce=none"));

        LaunchResult identities = run("identity", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identities.code(), identities.err());
        assertTrue(identities.out().contains("identities=0"));
    }

    @Test
    void bundleStatusMarksRuntimeRecordStaleWhenDeclarationDigestChanges() throws Exception {
        Path stateDir = tempDir.resolve("stale-runtime-state");
        LaunchResult add = installedAdd(stateDir);
        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());
        String changedDigest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        new BundleDesiredStateStore(stateDir).write(BundleDesiredState.empty()
                .addOrReplace(authorityBundle(changedDigest, IMAGE_DIGEST)));

        LaunchResult status = run("bundle", "status", "sample-authority", "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("desired=DECLARED"));
        assertTrue(status.out().contains("digest=" + changedDigest));
        assertTrue(status.out().contains("runtimeStatus=STALE_RENDERED"));
    }

    @Test
    void bundleAddWithProductionPlacementRendersHelmManagedDeployment() throws Exception {
        Path stateDir = tempDir.resolve("cluster-render-state");

        LaunchResult add = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--placement-profile=small-production",
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());
        assertTrue(add.out().contains("status=RENDERED"));
        Path renderedDir = stateDir.resolve("bundle-instances").resolve("instance-sample-authority");
        String deployment = Files.readString(renderedDir.resolve("helm").resolve("templates").resolve("backend-deployment.yaml"));
        assertTrue(deployment.contains("kind: Deployment"));
        assertTrue(deployment.contains("app.kubernetes.io/managed-by: \"Helm\""));
        assertTrue(deployment.contains("sh.harold.fulcrum/profile: \"small-production\""));
        assertTrue(deployment.contains("http://fulcrum-controller-service:18085/authority-backends/register"));
        assertTrue(Files.readString(renderedDir.resolve("checksums.txt"))
                .contains("helm/templates/backend-deployment.yaml="));
    }

    @Test
    void bundleStatusDoesNotEchoRunningRecordWithoutRuntimeObservation() {
        Path stateDir = tempDir.resolve("unobserved-runtime-state");
        new BundleDesiredStateStore(stateDir).write(BundleDesiredState.empty()
                .addOrReplace(authorityBundle(DIGEST, IMAGE_DIGEST)));
        new BundleInstanceStateStore(stateDir).append(new BundleInstanceRecord(
                "sample-authority",
                "RUNNING",
                DIGEST,
                Optional.of("instance-sample-authority"),
                Optional.of("shape-sample-authority"),
                Optional.of("manifest-sample-authority"),
                Optional.of(stateDir.resolve("bundle-instances").resolve("instance-sample-authority")
                        .resolve("manifest.json").toString()),
                Optional.of("launch-sample-authority"),
                Optional.of("runtime=compose|exitCode=0"),
                Optional.of("registration-sample-authority"),
                Optional.of("registration-evidence-sample-authority"),
                Optional.of("grant-fingerprint"),
                java.time.Instant.parse("2026-06-22T12:00:00Z")));

        LaunchResult status = run("bundle", "status", "sample-authority", "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("runtimeStatus=UNOBSERVED_RUNNING"));
    }

    @Test
    void bundleClaimingUngrantedAuthorityDomainIsDeniedFailClosed() throws Exception {
        Path stateDir = tempDir.resolve("denied-state");

        LaunchResult add = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=other.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());
        assertTrue(add.out().contains("status=DENIED"));
        assertTrue(add.out().contains("reason=GRANT_NOT_AUTHORIZED"));
        assertTrue(Files.readString(stateDir.resolve(BundleReceiptStore.FILE_NAME)).contains("\"status\":\"DENIED\""));

        LaunchResult identities = run("identity", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identities.code(), identities.err());
        assertTrue(identities.out().contains("identities=0"));
    }

    @Test
    void contributionBundleReconcilesThroughContributionStagingState() throws Exception {
        Path stateDir = tempDir.resolve("contribution-state");

        LaunchResult add = run(
                "bundle",
                "add",
                "sample-contribution",
                "--state-dir=" + stateDir,
                "--kind=contribution",
                "--artifact=oci://ghcr.io/harolddotsh/sample-contribution@" + DIGEST,
                "--digest=" + DIGEST,
                "--descriptor-digest=" + DESCRIPTOR_DIGEST,
                "--contribution=Paper.ChatPipeline:network:10",
                "--authority-domain=sample.contribution",
                "--resource-class=sample-contribution-host",
                "--granted-authority-domain=sample.contribution",
                "--granted-resource-class=sample-contribution-host",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());
        assertTrue(add.out().contains("status=STAGE_BLOCKED"));
        assertTrue(add.out().contains("grantState=REVOKED"));
        assertTrue(add.out().contains("bundle artifact not found"));
        assertTrue(add.out().contains("instanceId=none"));
        assertTrue(add.out().contains("contributionEvidence="));
        assertTrue(Files.readString(stateDir.resolve(BundleDesiredStateStore.FILE_NAME))
                .contains("\"kind\":\"contribution\""));
        assertTrue(Files.readString(stateDir.resolve(BundleContributionStateStore.FILE_NAME))
                .contains("\"status\":\"STAGE_BLOCKED\""));
        assertTrue(Files.readString(stateDir.resolve(BundleInstallGrantStateStore.FILE_NAME))
                .contains("\"status\":\"REVOKED\""));
        assertFalse(Files.exists(stateDir.resolve("bundle-instances")));

        LaunchResult list = run("bundle", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, list.code(), list.err());
        assertTrue(list.out().contains("bundle=sample-contribution"));
        assertTrue(list.out().contains("kind=contribution"));
        assertTrue(list.out().contains("runtimeStatus=STAGE_BLOCKED"));
        assertTrue(list.out().contains("instance=none"));

        LaunchResult status = run("bundle", "status", "sample-contribution", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("desired=DECLARED"));
        assertTrue(status.out().contains("runtimeStatus=STAGE_BLOCKED"));
        assertTrue(status.out().contains("grantStatus=REVOKED"));
        assertTrue(status.out().contains("contributionCachePath=none"));
        assertTrue(status.out().contains("contributionEvidence="));
    }

    @Test
    void bundleAddRequiresDigestPinnedOciReferenceAndSignatureEvidence() {
        Path stateDir = tempDir.resolve("signature-state");

        LaunchResult missingEvidence = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend");

        assertEquals(FulcrumLauncher.USAGE_ERROR, missingEvidence.code());
        assertTrue(missingEvidence.err().contains("Missing required option --signature-evidence"));
        assertFalse(Files.exists(stateDir.resolve(BundleDesiredStateStore.FILE_NAME)));

        LaunchResult localSource = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=file:///tmp/sample-authority.jar",
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.USAGE_ERROR, localSource.code());
        assertTrue(localSource.err().contains("requires a digest-pinned OCI artifact reference"));
    }

    @Test
    void bundleRemoveRevokesIdentityForLatestReceipt() throws Exception {
        Path stateDir = tempDir.resolve("remove-state");
        LaunchResult add = installedAdd(stateDir);
        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, add.code(), add.err());

        LaunchResult remove = run("bundle", "remove", "sample-authority", "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, remove.code(), remove.err());
        assertTrue(remove.out().contains("status=REMOVED"));
        assertTrue(remove.out().contains("grantState=REVOKED"));
        assertFalse(BundleDesiredState.fromJson(read(stateDir.resolve(BundleDesiredStateStore.FILE_NAME)))
                .find("sample-authority")
                .isPresent());
        LaunchResult status = run("bundle", "status", "sample-authority", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, status.code(), status.err());
        assertTrue(status.out().contains("desired=ABSENT"));
        assertTrue(status.out().contains("runtimeStatus=REMOVED"));
        assertTrue(status.out().contains("grantStatus=REVOKED"));

        LaunchResult identities = run("identity", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identities.code(), identities.err());
        assertTrue(identities.out().contains("identities=0"));

        LaunchResult identity = run("identity", "inspect", "sample-authority", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identity.code(), identity.err());
        assertTrue(identity.out().contains("status=REVOKED"));
        assertTrue(identity.out().contains("evidence=grant=REVOKED"));
    }

    @Test
    void directBundleMutationIsScopedToSingleMachineOrTestNetworkBreakGlass() throws Exception {
        Path stateDir = tempDir.resolve("direct-state");

        LaunchResult productionDirect = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--profile=small-production",
                "--direct",
                "--break-glass-ticket=OPS-123",
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.USAGE_ERROR, productionDirect.code());
        assertTrue(productionDirect.err().contains("direct bundle mutation is only available"));
        assertFalse(Files.exists(stateDir.resolve("direct-mutation-audit.jsonl")));

        LaunchResult singleMachineDirect = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--direct",
                "--break-glass-ticket=OPS-124",
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.CONFIGURATION_BLOCKED, singleMachineDirect.code(), singleMachineDirect.err());
        assertTrue(Files.readString(stateDir.resolve("direct-mutation-audit.jsonl")).contains("OPS-124"));
    }

    private LaunchResult installedAdd(Path stateDir) {
        return run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--backend-image=ghcr.io/harolddotsh/sample-authority-backend@" + IMAGE_DIGEST,
                "--backend-image-digest=" + IMAGE_DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");
    }

    private static DeclaredBundle authorityBundle(String digest, String imageDigest) {
        return new DeclaredBundle(
                "sample-authority",
                "oci://ghcr.io/harolddotsh/sample-authority@" + digest,
                digest,
                "authority-backend",
                "network",
                "single-machine",
                Optional.of("full-engine"),
                Optional.of("ghcr.io/harolddotsh/sample-authority-backend@" + imageDigest),
                Optional.of(imageDigest),
                List.of("sample.authority"),
                List.of("sample-backend"),
                Optional.empty(),
                List.of(),
                true);
    }

    private LaunchResult run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher(RuntimeEnvironment.of(Map.of(
                "FULCRUM_BUNDLE_RUNTIME_ADAPTER", "render-only"))).run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new LaunchResult(
                code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8)
        );
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path);
    }

    private record LaunchResult(int code, String out, String err) {
    }
}
