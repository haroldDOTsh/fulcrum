package sh.harold.fulcrum.host.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record HostCredentialScope(Set<HostResourceGrant> grants) {
    public HostCredentialScope {
        grants = Set.copyOf(Objects.requireNonNull(grants, "grants"));
    }

    public static HostCredentialScope of(HostResourceGrant... grants) {
        return new HostCredentialScope(Arrays.stream(Objects.requireNonNull(grants, "grants"))
                .collect(Collectors.toUnmodifiableSet()));
    }

    public boolean permits(HostResourceFamily resourceFamily, HostAccessMode accessMode, String resourceName) {
        return permits(new HostResourceGrant(resourceFamily, accessMode, resourceName));
    }

    public boolean permits(HostResourceGrant grant) {
        return grants.contains(Objects.requireNonNull(grant, "grant"));
    }
}
