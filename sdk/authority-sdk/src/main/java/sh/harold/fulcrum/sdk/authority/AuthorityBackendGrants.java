package sh.harold.fulcrum.sdk.authority;

import sh.harold.fulcrum.host.api.HostAccessMode;
import sh.harold.fulcrum.host.api.HostResourceFamily;
import sh.harold.fulcrum.host.api.HostResourceGrant;

public final class AuthorityBackendGrants {
    private AuthorityBackendGrants() {
    }

    public static HostResourceGrant authorityDomain(String authorityDomain) {
        return new HostResourceGrant(
                HostResourceFamily.AUTHORITY_DOMAIN,
                HostAccessMode.PRODUCE,
                AuthoritySdkNames.requireNonBlank(authorityDomain, "authorityDomain"));
    }

    public static HostResourceGrant resourceClass(String resourceClass) {
        return new HostResourceGrant(
                HostResourceFamily.RESOURCE_CLASS,
                HostAccessMode.READ,
                AuthoritySdkNames.requireNonBlank(resourceClass, "resourceClass"));
    }
}
