package sh.harold.fulcrum.registry.authority;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityRuntimeAdmissionTest {
    @Test
    void defaultsAcceptRequiredTargetWorkers() {
        AuthorityRuntimeAdmission.Report report = AuthorityRuntimeAdmission.inspect(Map.of());

        assertThat(report.accepted()).isTrue();
        assertThat(report.commandWorkersEnabled()).isTrue();
        assertThat(report.stateProjectionWorkersEnabled()).isTrue();
        assertThat(report.idempotencyCacheEnabled()).isTrue();
        assertThat(report.snapshotCacheEnabled()).isTrue();
        assertThat(report.violations()).isEmpty();
    }

    @Test
    void rejectsDisabledCommandWorkers() {
        AuthorityRuntimeAdmission.Report report = AuthorityRuntimeAdmission.inspect(Map.of(
            "command-worker", Map.of("enabled", false)
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.violations()).containsExactly(
            "authority.command-worker.enabled must be true"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.command-worker.enabled must be true");
    }

    @Test
    void rejectsDisabledStateProjectionWorkers() {
        AuthorityRuntimeAdmission.Report report = AuthorityRuntimeAdmission.inspect(Map.of(
            "state-projection-worker", Map.of("enabled", "false")
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.violations()).containsExactly(
            "authority.state-projection-worker.enabled must be true"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.state-projection-worker.enabled must be true");
    }

    @Test
    void rejectsDisabledIdempotencyCache() {
        AuthorityRuntimeAdmission.Report report = AuthorityRuntimeAdmission.inspect(Map.of(
            "idempotency-cache", Map.of("enabled", false)
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.idempotencyCacheEnabled()).isFalse();
        assertThat(report.violations()).containsExactly(
            "authority.idempotency-cache.enabled must be true"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.idempotency-cache.enabled must be true");
    }

    @Test
    void rejectsDisabledSnapshotCache() {
        AuthorityRuntimeAdmission.Report report = AuthorityRuntimeAdmission.inspect(Map.of(
            "snapshot-cache", Map.of("enabled", "false")
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.snapshotCacheEnabled()).isFalse();
        assertThat(report.violations()).containsExactly(
            "authority.snapshot-cache.enabled must be true"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.snapshot-cache.enabled must be true");
    }

    @Test
    void registryWiresValkeyCacheSectionsToTheirAuthorityRoles() throws Exception {
        String source = Files.readString(registryServicePath());

        assertThat(methodSlice(
            source,
            "private ValkeyAuthoritySnapshotCacheProjection createAuthoritySnapshotCacheProjection",
            "private static AuthorityStateRestoreTarget fanoutRestoreTarget"
        ))
            .contains("getOrDefault(\"snapshot-cache\", Map.of())")
            .doesNotContain("getOrDefault(\"idempotency-cache\", Map.of())");
        assertThat(methodSlice(
            source,
            "private DataAuthority.CommandPort createAuthorityCommandCache",
            "private AuthoritySubstratePreflight.ActualSubstrate authorityActualSubstrate"
        ))
            .contains("getOrDefault(\"idempotency-cache\", Map.of())")
            .doesNotContain("getOrDefault(\"snapshot-cache\", Map.of())");
    }

    private static Path registryServicePath() {
        Path fromModule = Path.of(
            "src",
            "main",
            "java",
            "sh",
            "harold",
            "fulcrum",
            "registry",
            "RegistryService.java"
        );
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return Path.of(
            "registry-service",
            "src",
            "main",
            "java",
            "sh",
            "harold",
            "fulcrum",
            "registry",
            "RegistryService.java"
        );
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker);
        assertThat(start).as(startMarker).isNotNegative();
        assertThat(end).as(endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }
}
