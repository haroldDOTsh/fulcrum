package sh.harold.fulcrum.api.data.impl.authority.events;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityProjectionManifestTest {
    @Test
    void explicitManifestAcceptsOnlyDeclaredEventTypes() {
        AuthorityProjectionManifest manifest = AuthorityProjectionManifest.of(
            "rank-projection",
            "rank-v1",
            List.of("REVOKE_RANK", "GRANT_RANK", "GRANT_RANK")
        );

        assertThat(manifest.acceptedEventTypes()).containsExactly("GRANT_RANK", "REVOKE_RANK");
        assertThat(manifest.acceptsEventType("GRANT_RANK")).isTrue();
        assertThat(manifest.acceptsEventType("START_SESSION")).isFalse();
        assertThat(manifest.acceptsAllEventTypes()).isFalse();
    }

    @Test
    void manifestFingerprintIsStableAcrossEventTypeOrder() {
        AuthorityProjectionManifest first = AuthorityProjectionManifest.of(
            "rank-projection",
            "rank-v1",
            List.of("REVOKE_RANK", "GRANT_RANK")
        );
        AuthorityProjectionManifest second = AuthorityProjectionManifest.of(
            "rank-projection",
            "rank-v1",
            List.of("GRANT_RANK", "REVOKE_RANK")
        );

        assertThat(first.manifestFingerprint()).isEqualTo(second.manifestFingerprint());
        assertThat(first.manifestPayload()).isEqualTo(second.manifestPayload());
    }

    @Test
    void compatibilityManifestAcceptsAllEventTypes() {
        AuthorityProjectionManifest manifest = AuthorityProjectionManifest.unversioned("legacy-projection");

        assertThat(manifest.acceptsAllEventTypes()).isTrue();
        assertThat(manifest.acceptsEventType("ANY_EVENT")).isTrue();
        assertThat(manifest.projectionVersion()).isEqualTo("unversioned");
        assertThat(manifest.manifestFingerprint()).isNotBlank();
    }
}
