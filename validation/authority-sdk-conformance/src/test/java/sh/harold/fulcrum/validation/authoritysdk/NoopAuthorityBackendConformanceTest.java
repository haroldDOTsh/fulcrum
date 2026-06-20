package sh.harold.fulcrum.validation.authoritysdk;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.control.registration.CapabilityBackendRegistrationController;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationRejectionReason;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationStatus;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NoopAuthorityBackendConformanceTest {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("backend-noop");

    @Test
    void noOpBackendSelfRegistersThroughPublishedSdkClient() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        NoopAuthorityBackend backend = new NoopAuthorityBackend(
                controller,
                securityContext(Set.of(
                        AuthorityBackendGrants.authorityDomain(NoopAuthorityBackend.AUTHORITY_DOMAIN),
                        AuthorityBackendGrants.resourceClass(NoopAuthorityBackend.RESOURCE_CLASS))),
                NOW);

        AuthorityBackendRegistrationReceipt receipt = backend.start();

        assertEquals(AuthorityBackendRegistrationStatus.ADMITTED, receipt.status());
        assertEquals(Optional.of(PRINCIPAL), receipt.principalId());
        assertEquals(1, receipt.fencingEpoch());
        assertTrue(receipt.materializationPlanHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void noOpBackendCannotStartWhenCredentialLacksAuthorityGrant() {
        CapabilityBackendRegistrationController controller = new CapabilityBackendRegistrationController();
        NoopAuthorityBackend backend = new NoopAuthorityBackend(
                controller,
                securityContext(Set.of(AuthorityBackendGrants.resourceClass(NoopAuthorityBackend.RESOURCE_CLASS))),
                NOW);

        AuthorityBackendRegistrationReceipt receipt = backend.register();
        IllegalStateException exception = assertThrows(IllegalStateException.class, backend::start);

        assertEquals(AuthorityBackendRegistrationStatus.DENIED, receipt.status());
        assertEquals(Optional.of(AuthorityBackendRegistrationRejectionReason.MISSING_AUTHORITY_DOMAIN_GRANT), receipt.rejectionReason());
        assertTrue(exception.getMessage().contains("MISSING_AUTHORITY_DOMAIN_GRANT"));
    }

    @Test
    void noOpBackendSelfRegistersInSeparateJvmThroughSdkServiceLoader() throws Exception {
        Process process = new ProcessBuilder(
                javaBinary(),
                "-cp",
                System.getProperty("java.class.path"),
                "sh.harold.fulcrum.validation.authoritysdk.NoopAuthorityBackendProcess")
                .redirectErrorStream(true)
                .start();

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("status=ADMITTED"), output);
        assertTrue(output.contains("capabilityId=noop-backend"), output);
        assertTrue(output.contains("fencingEpoch=1"), output);
    }

    private static HostSecurityContext securityContext(Set<HostResourceGrant> grants) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-noop-backend"),
                        "authority-backend",
                        new PoolId("pool-authority"),
                        new MachineRef("machine-authority"),
                        PRINCIPAL),
                "service-account:noop-backend",
                new HostCredentialScope(grants));
    }

    private static String javaBinary() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
