package sh.harold.fulcrum.api.data.impl.postgres;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresConnectionAdapterTest {
    @Test
    void defaultsToPgBouncerTransactionPoolingSafeDriverSettings() {
        HikariConfig config = new HikariConfig();

        PostgresConnectionAdapter.configurePostgresDataSourceProperties(config, new Properties());

        Properties properties = config.getDataSourceProperties();
        assertThat(properties.getProperty("prepareThreshold")).isEqualTo("0");
        assertThat(properties.getProperty("preparedStatementCacheQueries")).isEqualTo("0");
        assertThat(properties.getProperty("reWriteBatchedInserts")).isEqualTo("true");
        assertThat(properties.containsKey("useServerPrepStmts")).isFalse();
    }

    @Test
    void directPostgresModeKeepsLegacyPreparedStatementSettings() {
        HikariConfig config = new HikariConfig();
        Properties additionalProperties = new Properties();
        additionalProperties.setProperty("pgbouncer-transaction-pooling", "false");

        PostgresConnectionAdapter.configurePostgresDataSourceProperties(config, additionalProperties);

        Properties properties = config.getDataSourceProperties();
        assertThat(properties.getProperty("useServerPrepStmts")).isEqualTo("true");
        assertThat(properties.getProperty("cachePrepStmts")).isEqualTo("true");
        assertThat(properties.getProperty("prepareThreshold")).isNull();
    }

    @Test
    void transactionPoolingRejectsServerPreparedStatementOverrides() {
        Properties serverPreparedStatements = new Properties();
        serverPreparedStatements.setProperty("useServerPrepStmts", "true");

        assertThatThrownBy(() -> PostgresConnectionAdapter.configurePostgresDataSourceProperties(
            new HikariConfig(),
            serverPreparedStatements
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not allow useServerPrepStmts=true");

        Properties prepareThreshold = new Properties();
        prepareThreshold.setProperty("prepareThreshold", "5");

        assertThatThrownBy(() -> PostgresConnectionAdapter.configurePostgresDataSourceProperties(
            new HikariConfig(),
            prepareThreshold
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prepareThreshold=0");
    }
}
