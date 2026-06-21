package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OperatorBundleCliTest {
    private static final String DIGEST = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @TempDir
    private Path tempDir;

    @Test
    void bundleAddWritesDesiredStateReconcilesAndIssuesInstallIdentity() throws Exception {
        Path stateDir = tempDir.resolve("install-state");

        LaunchResult add = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.OK, add.code(), add.err());
        assertTrue(add.out().contains("status=INSTALLED"));
        assertTrue(add.out().contains("grantFingerprint="));
        assertFalse(add.out().contains("grantFingerprint=none"));
        assertTrue(Files.readString(stateDir.resolve(BundleDesiredStateStore.FILE_NAME)).contains("sample-authority"));
        assertTrue(Files.readString(stateDir.resolve(BundleArtifactVerificationStore.FILE_NAME)).contains("cosign:test"));
        assertTrue(Files.readString(stateDir.resolve(BundleReceiptStore.FILE_NAME)).contains("\"status\":\"INSTALLED\""));

        LaunchResult list = run("bundle", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, list.code(), list.err());
        assertTrue(list.out().contains("bundles=1"));
        assertTrue(list.out().contains("bundle=sample-authority"));

        LaunchResult identities = run("identity", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identities.code(), identities.err());
        assertTrue(identities.out().contains("identities=1"));
        assertTrue(identities.out().contains("credential=install://bundle/sample-authority/credential"));
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
    void bundleAddRequiresDigestPinnedOciReferenceAndSignatureEvidence() {
        Path stateDir = tempDir.resolve("signature-state");

        LaunchResult missingEvidence = run(
                "bundle",
                "add",
                "sample-authority",
                "--state-dir=" + stateDir,
                "--artifact=oci://ghcr.io/harolddotsh/sample-authority@" + DIGEST,
                "--digest=" + DIGEST,
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
        assertEquals(FulcrumLauncher.OK, add.code(), add.err());

        LaunchResult remove = run("bundle", "remove", "sample-authority", "--state-dir=" + stateDir);

        assertEquals(FulcrumLauncher.OK, remove.code(), remove.err());
        assertTrue(remove.out().contains("status=REMOVED"));
        assertFalse(BundleDesiredState.fromJson(read(stateDir.resolve(BundleDesiredStateStore.FILE_NAME)))
                .find("sample-authority")
                .isPresent());

        LaunchResult identities = run("identity", "list", "--state-dir=" + stateDir);
        assertEquals(FulcrumLauncher.OK, identities.code(), identities.err());
        assertTrue(identities.out().contains("identities=0"));
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
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");

        assertEquals(FulcrumLauncher.OK, singleMachineDirect.code(), singleMachineDirect.err());
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
                "--authority-domain=sample.authority",
                "--resource-class=sample-backend",
                "--granted-authority-domain=sample.authority",
                "--granted-resource-class=sample-backend",
                "--signature-evidence=cosign:test");
    }

    private LaunchResult run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new FulcrumLauncher(RuntimeEnvironment.of(Map.of())).run(
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
