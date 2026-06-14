package sh.harold.fulcrum.api.data.impl.postgres;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresConnectionBudgetTest {

    @Test
    void reportSortsDeclarationsAndProducesStableFingerprint() {
        PostgresConnectionBudget.Declaration snapshots = PostgresConnectionBudget.declaration(
            "registry-service:node-snapshots",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            "registry-fulcrum",
            2,
            0,
            5000L
        );
        PostgresConnectionBudget.Declaration authority = PostgresConnectionBudget.declaration(
            "registry-service:central-authority",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            "FulcrumPostgresPool-authority-fulcrum",
            2,
            0,
            5000L
        );

        PostgresConnectionBudget.Report first = PostgresConnectionBudget.inspect(
            List.of(snapshots, authority),
            8
        );
        PostgresConnectionBudget.Report second = PostgresConnectionBudget.inspect(
            List.of(authority, snapshots),
            8
        );

        assertThat(first.accepted()).isTrue();
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.totalDeclaredMaxPoolSize()).isEqualTo(4);
        assertThat(first.declarations())
            .extracting(PostgresConnectionBudget.Declaration::poolName)
            .containsExactly("FulcrumPostgresPool-authority-fulcrum", "registry-fulcrum");
    }

    @Test
    void reportRejectsTotalOverCeiling() {
        PostgresConnectionBudget.Report report = PostgresConnectionBudget.inspect(
            List.of(PostgresConnectionBudget.declaration(
                "registry-service:central-authority",
                "registry-service",
                PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
                "authority",
                5,
                1,
                5000L
            )),
            4
        );

        assertThat(report.accepted()).isFalse();
        assertThat(report.violations()).containsExactly(
            "declared Postgres max pool size 5 exceeds allowed total 4"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("declared Postgres max pool size 5 exceeds allowed total 4");
    }

    @Test
    void reportRejectsPositiveGameNodePostgresPool() {
        PostgresConnectionBudget.Report report = PostgresConnectionBudget.inspect(
            List.of(PostgresConnectionBudget.declaration(
                "paper-runtime:data-authority",
                "runtime",
                PostgresConnectionBudget.GAME_NODE_BOUNDARY,
                "game-node-postgres",
                1,
                0,
                5000L
            )),
            8
        );

        assertThat(report.accepted()).isFalse();
        assertThat(report.violations()).containsExactly(
            "game-node boundary may not declare Postgres pool game-node-postgres"
        );
    }

    @Test
    void declarationParsesHyphenatedPoolProperties() {
        Properties properties = new Properties();
        properties.setProperty("maximum-pool-size", "3");
        properties.setProperty("minimum-idle", "1");
        properties.setProperty("connection-timeout", "7000");

        PostgresConnectionBudget.Declaration declaration = PostgresConnectionBudget.fromPoolProperties(
            "registry-service:central-authority",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            "authority",
            properties,
            4,
            0,
            5000L
        );

        assertThat(declaration.declaredMaxPoolSize()).isEqualTo(3);
        assertThat(declaration.minimumIdle()).isEqualTo(1);
        assertThat(declaration.connectionTimeoutMillis()).isEqualTo(7000L);
    }

    @Test
    void declarationRejectsIdleAboveMaximum() {
        assertThatThrownBy(() -> PostgresConnectionBudget.declaration(
            "registry-service:central-authority",
            "registry-service",
            PostgresConnectionBudget.REGISTRY_SERVICE_BOUNDARY,
            "authority",
            1,
            2,
            5000L
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumIdle must be <= declaredMaxPoolSize");
    }
}
