package sh.harold.fulcrum.registry;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionBudget;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryServicePostgresConnectionBudgetTest {

    @Test
    void authorityFlagDoesNotDeclareRegistryAuthorityPool() {
        PostgresConnectionBudget.Report report = RegistryService.buildPostgresConnectionBudget(
            config(true, true, 3, 1, 6)
        );

        assertThat(report.accepted()).isTrue();
        assertThat(report.totalDeclaredMaxPoolSize()).isEqualTo(3);
        assertThat(report.maxTotalPoolSize()).isEqualTo(6);
        assertThat(report.declarations())
            .extracting(PostgresConnectionBudget.Declaration::poolName)
            .containsExactly("registry-fulcrum");
        assertThat(report.declarations())
            .extracting(PostgresConnectionBudget.Declaration::ownerRole)
            .containsExactly("registry-service:node-snapshots");
        assertThat(report.declarations())
            .extracting(PostgresConnectionBudget.Declaration::allowedRuntimeBoundary)
            .containsOnly(PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY);
    }

    @Test
    void declaresSnapshotPoolOnlyWhenAuthorityDisabled() {
        PostgresConnectionBudget.Report report = RegistryService.buildPostgresConnectionBudget(
            config(true, false, 2, 0, 8)
        );

        assertThat(report.accepted()).isTrue();
        assertThat(report.totalDeclaredMaxPoolSize()).isEqualTo(2);
        assertThat(report.declarations())
            .extracting(PostgresConnectionBudget.Declaration::poolName)
            .containsExactly("registry-fulcrum");
    }

    @Test
    void postgresDoesNotImplicitlyEnableAuthorityPool() {
        PostgresConnectionBudget.Report report = RegistryService.buildPostgresConnectionBudget(
            configWithoutAuthority(true, 2, 0, 8)
        );

        assertThat(report.accepted()).isTrue();
        assertThat(report.totalDeclaredMaxPoolSize()).isEqualTo(2);
        assertThat(report.declarations())
            .extracting(PostgresConnectionBudget.Declaration::ownerRole)
            .containsExactly("registry-service:node-snapshots");
    }

    @Test
    void disabledPostgresDeclaresNoPools() {
        PostgresConnectionBudget.Report report = RegistryService.buildPostgresConnectionBudget(
            config(false, true, 2, 0, 8)
        );

        assertThat(report.accepted()).isTrue();
        assertThat(report.totalDeclaredMaxPoolSize()).isZero();
        assertThat(report.declarations()).isEmpty();
    }

    @Test
    void configuredCeilingFlagsOverBudgetDocket() {
        PostgresConnectionBudget.Report report = RegistryService.buildPostgresConnectionBudget(
            config(true, true, 5, 1, 4)
        );

        assertThat(report.accepted()).isFalse();
        assertThat(report.totalDeclaredMaxPoolSize()).isEqualTo(5);
        assertThat(report.violations()).containsExactly(
            "declared Postgres max pool size 5 exceeds allowed total 4"
        );
    }

    private static Map<String, Object> config(boolean postgresEnabled,
                                              boolean authorityEnabled,
                                              int maximumPoolSize,
                                              int minimumIdle,
                                              int maxTotalPoolSize) {
        return Map.of(
            "postgres", Map.of(
                "enabled", postgresEnabled,
                "database", "fulcrum",
                "pool", Map.of(
                    "maximum-pool-size", maximumPoolSize,
                    "minimum-idle", minimumIdle,
                    "connection-timeout", 7000
                ),
                "connection-budget", Map.of(
                    "max-total-pool-size", maxTotalPoolSize,
                    "enforce", true
                )
            ),
            "authority", Map.of(
                "enabled", authorityEnabled
            )
        );
    }

    private static Map<String, Object> configWithoutAuthority(boolean postgresEnabled,
                                                              int maximumPoolSize,
                                                              int minimumIdle,
                                                              int maxTotalPoolSize) {
        return Map.of(
            "postgres", Map.of(
                "enabled", postgresEnabled,
                "database", "fulcrum",
                "pool", Map.of(
                    "maximum-pool-size", maximumPoolSize,
                    "minimum-idle", minimumIdle,
                    "connection-timeout", 7000
                ),
                "connection-budget", Map.of(
                    "max-total-pool-size", maxTotalPoolSize,
                    "enforce", true
                )
            )
        );
    }
}
