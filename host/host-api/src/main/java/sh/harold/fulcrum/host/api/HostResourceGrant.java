package sh.harold.fulcrum.host.api;

import java.util.Objects;

public record HostResourceGrant(
        HostResourceFamily resourceFamily,
        HostAccessMode accessMode,
        String resourceName) {
    public HostResourceGrant {
        resourceFamily = Objects.requireNonNull(resourceFamily, "resourceFamily");
        accessMode = Objects.requireNonNull(accessMode, "accessMode");
        resourceName = HostNames.requireNonBlank(resourceName, "resourceName");
    }
}
