package sh.harold.fulcrum.validation.authoritysdk;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationClient;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendRegistrationReceipt;

import java.time.Instant;
import java.util.ServiceLoader;
import java.util.Set;

public final class NoopAuthorityBackendProcess {
    private static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    private static final PrincipalId PRINCIPAL = new PrincipalId("backend-noop-process");

    private NoopAuthorityBackendProcess() {
    }

    public static void main(String[] args) {
        AuthorityBackendRegistrationClient client = ServiceLoader.load(AuthorityBackendRegistrationClient.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no authority backend registration client available"));
        AuthorityBackendRegistrationReceipt receipt = new NoopAuthorityBackend(
                client,
                securityContext(Set.of(
                        AuthorityBackendGrants.authorityDomain(NoopAuthorityBackend.AUTHORITY_DOMAIN),
                        AuthorityBackendGrants.resourceClass(NoopAuthorityBackend.RESOURCE_CLASS))),
                NOW).start();
        System.out.println(receipt.signedWireValue());
    }

    private static HostSecurityContext securityContext(Set<HostResourceGrant> grants) {
        return new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-noop-backend-process"),
                        "authority-backend",
                        new PoolId("pool-authority"),
                        new MachineRef("machine-authority"),
                        PRINCIPAL),
                "service-account:noop-backend-process",
                new HostCredentialScope(grants));
    }
}
