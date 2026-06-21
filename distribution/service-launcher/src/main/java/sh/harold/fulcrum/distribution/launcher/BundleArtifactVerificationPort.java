package sh.harold.fulcrum.distribution.launcher;

import sh.harold.fulcrum.sdk.authority.AuthorityArtifactVerificationEvidence;

import java.util.Optional;

interface BundleArtifactVerificationPort {
    Optional<AuthorityArtifactVerificationEvidence> verify(DeclaredBundle bundle);
}
