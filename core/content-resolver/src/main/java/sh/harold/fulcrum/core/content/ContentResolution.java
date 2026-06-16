package sh.harold.fulcrum.core.content;

import sh.harold.fulcrum.core.manifest.ResolvedManifest;

import java.util.Objects;
import java.util.Optional;

public record ContentResolution(
        ContentResolutionStatus status,
        Optional<ResolvedManifest> resolvedManifest,
        ContentResolutionTrace trace) {
    public ContentResolution {
        status = Objects.requireNonNull(status, "status");
        resolvedManifest = resolvedManifest == null ? Optional.empty() : resolvedManifest;
        trace = Objects.requireNonNull(trace, "trace");
        if (status == ContentResolutionStatus.RESOLVED && resolvedManifest.isEmpty()) {
            throw new IllegalArgumentException("resolved status requires a manifest");
        }
        if (status == ContentResolutionStatus.REJECTED && resolvedManifest.isPresent()) {
            throw new IllegalArgumentException("rejected status must not carry a manifest");
        }
    }

    static ContentResolution resolved(ResolvedManifest manifest, ContentResolutionTrace trace) {
        return new ContentResolution(ContentResolutionStatus.RESOLVED, Optional.of(manifest), trace);
    }

    static ContentResolution rejected(ContentResolutionTrace trace) {
        return new ContentResolution(ContentResolutionStatus.REJECTED, Optional.empty(), trace);
    }
}
