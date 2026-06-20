package sh.harold.fulcrum.host.api;

import java.util.Set;

public interface HostMenuContribution {
    default Set<String> commandAliases() {
        return Set.of();
    }

    HostMenuRenderFrame open(HostMenuOpenRequest request);

    HostMenuRenderFrame click(HostMenuClickRequest request);
}
