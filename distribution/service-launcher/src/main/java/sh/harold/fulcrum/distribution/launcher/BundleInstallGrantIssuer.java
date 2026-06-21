package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.MachineRef;
import sh.harold.fulcrum.api.kernel.PoolId;
import sh.harold.fulcrum.host.api.HostCredentialScope;
import sh.harold.fulcrum.host.api.HostInstanceIdentity;
import sh.harold.fulcrum.host.api.HostResourceGrant;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendDescriptorDigests;
import sh.harold.fulcrum.sdk.authority.AuthorityBackendGrants;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BundleInstallGrantIssuer {
    Optional<IssuedBundleGrant> issue(DeclaredBundle bundle, BundleReconcileAuthorization authorization) {
        if (!authorization.authorityDomains().containsAll(bundle.authorityDomains())
                || !authorization.resourceClasses().containsAll(bundle.resourceClasses())) {
            return Optional.empty();
        }

        List<HostResourceGrant> grants = new ArrayList<>();
        bundle.authorityDomains().stream()
                .map(AuthorityBackendGrants::authorityDomain)
                .forEach(grants::add);
        bundle.resourceClasses().stream()
                .map(AuthorityBackendGrants::resourceClass)
                .forEach(grants::add);
        HostCredentialScope scope = new HostCredentialScope(java.util.Set.copyOf(grants));
        HostSecurityContext securityContext = new HostSecurityContext(
                new HostInstanceIdentity(
                        new InstanceId("instance-" + bundle.id()),
                        bundle.kind(),
                        new PoolId("pool-" + bundle.scope()),
                        new MachineRef("machine-" + bundle.placementProfile()),
                        new PrincipalId("principal-" + bundle.id())),
                "install://bundle/" + bundle.id() + "/credential",
                scope);
        return Optional.of(new IssuedBundleGrant(
                bundle.id(),
                securityContext,
                AuthorityBackendDescriptorDigests.grantFingerprint(scope)));
    }
}
