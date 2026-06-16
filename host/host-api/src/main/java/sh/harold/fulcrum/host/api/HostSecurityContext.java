package sh.harold.fulcrum.host.api;

import java.util.Objects;

public record HostSecurityContext(
        HostInstanceIdentity identity,
        String credentialRef,
        HostCredentialScope credentialScope) {
    public HostSecurityContext {
        identity = Objects.requireNonNull(identity, "identity");
        credentialRef = HostNames.requireNonBlank(credentialRef, "credentialRef");
        credentialScope = Objects.requireNonNull(credentialScope, "credentialScope");
    }
}
