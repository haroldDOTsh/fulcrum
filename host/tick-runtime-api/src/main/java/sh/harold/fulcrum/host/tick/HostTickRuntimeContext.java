package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.host.api.HostSessionAttachment;

import java.util.Objects;

public record HostTickRuntimeContext(HostSessionAttachment sessionAttachment) {
    public HostTickRuntimeContext {
        sessionAttachment = Objects.requireNonNull(sessionAttachment, "sessionAttachment");
    }
}
