package sh.harold.fulcrum.api.data.guard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameNodeStorageGuardTest {
    @Test
    void allowsRemoteAuthorityAndMessageBusConfiguration() {
        Map<String, Object> config = Map.of(
            "authority", Map.of(
                "mode", "remote",
                "server-id", "registry-service"
            ),
            "redis", Map.of(
                "host", "localhost",
                "port", 6379
            )
        );

        assertThat(GameNodeStorageGuard.inspectNoStoreGameNode(GameNodeStorageGuard.NodeKind.PAPER, config))
            .isEmpty();
    }

    @Test
    void rejectsPostgresSectionForGameNode() {
        Map<String, Object> config = Map.of(
            "postgres", Map.of(
                "jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum",
                "username", "fulcrum"
            ),
            "authority", Map.of("mode", "remote")
        );

        assertThatThrownBy(() -> GameNodeStorageGuard.requireNoStoreGameNode(
            GameNodeStorageGuard.NodeKind.VELOCITY,
            config
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("P3 no-store violation")
            .hasMessageContaining("postgres")
            .hasMessageContaining("postgres.jdbc-url");
    }

    @Test
    void rejectsDatabaseConnectionStringsOutsideNamedStoreSections() {
        Map<String, Object> config = Map.of(
            "authority", Map.of(
                "mode", "remote",
                "legacy-url", "jdbc:postgresql://localhost:5432/fulcrum"
            )
        );

        assertThat(GameNodeStorageGuard.inspectNoStoreGameNode(GameNodeStorageGuard.NodeKind.PAPER, config))
            .extracting(GameNodeStorageGuard.Violation::path)
            .containsExactly("authority.legacy-url");
    }

    @Test
    void rejectsMysqlSectionsAndGenericJdbcConnectionStrings() {
        Map<String, Object> config = Map.of(
            "mysql", Map.of("host", "localhost"),
            "authority", Map.of(
                "mode", "remote",
                "legacy-url", "jdbc:mysql://localhost:3306/fulcrum"
            )
        );

        assertThat(GameNodeStorageGuard.inspectNoStoreGameNode(GameNodeStorageGuard.NodeKind.PAPER, config))
            .extracting(GameNodeStorageGuard.Violation::path)
            .containsExactlyInAnyOrder("mysql", "mysql.host", "authority.legacy-url");
    }
}
