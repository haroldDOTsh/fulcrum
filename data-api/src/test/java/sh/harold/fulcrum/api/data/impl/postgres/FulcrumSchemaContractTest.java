package sh.harold.fulcrum.api.data.impl.postgres;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FulcrumSchemaContractTest {
    private static final Pattern TABLE_TARGET = Pattern.compile(
        "(?is)\\b(?:CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS|ALTER\\s+TABLE)\\s+([a-z_][a-z0-9_]*)"
    );

    @Test
    void schemaContractCoversEveryCanonicalMigrationTable() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        assertThat(contract.version()).isEqualTo(3);
        assertThat(contract.tableNames()).containsExactlyInAnyOrderElementsOf(migrationTables());
    }

    @Test
    void schemaContractFingerprintIsStable() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        assertThat(contract.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(contract.fingerprint()).isEqualTo(FulcrumSchemaContract.loadDefault().fingerprint());
    }

    @Test
    void tableContractsPointAtDataApiMigrationsAndNamedOwners() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        for (FulcrumSchemaContract.TableContract table : contract.tables().values()) {
            assertThat(table.ddlOwner())
                .as(table.tableName() + " ddl owner")
                .isEqualTo("data-api");
            assertThat(table.dataOwner())
                .as(table.tableName() + " data owner")
                .isNotBlank();
            assertThat(table.createdBy())
                .as(table.tableName() + " creation migration")
                .isIn(FulcrumDataMigrations.all());
            assertThat(resourceExists(table.createdBy()))
                .as(table.tableName() + " creation migration exists")
                .isTrue();
            assertThat(table.readers())
                .as(table.tableName() + " readers")
                .isNotEmpty();
            assertThat(table.writers())
                .as(table.tableName() + " writers")
                .isNotEmpty();
        }
    }

    @Test
    void createdByMigrationsCreateTheirContractedTables() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        for (FulcrumSchemaContract.TableContract table : contract.tables().values()) {
            String migrationSql = readResource(table.createdBy());
            assertThat(migrationSql)
                .as(table.tableName() + " created-by migration")
                .containsPattern(createTablePattern(table.tableName()));
        }
    }

    @Test
    void dataApiOwnedTableGateRequiresExplicitServiceAccess() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.loadDefault();

        assertThat(contract.requireDataApiOwnedTable("player_profiles", "registry-service").tableName())
            .isEqualTo("player_profiles");

        assertThatThrownBy(() -> contract.requireDataApiOwnedTable("player_profiles", "paper-game-node"))
            .hasMessageContaining("player_profiles")
            .hasMessageContaining("read access");
    }

    @Test
    void registrySnapshotTableIsDataApiOwnedAndRegistryConsumed() {
        FulcrumSchemaContract.TableContract table = FulcrumSchemaContract.loadDefault()
            .table("registry_node_snapshots");

        assertThat(table.ddlOwner()).isEqualTo("data-api");
        assertThat(table.dataOwner()).isEqualTo("registry-control-plane");
        assertThat(table.createdBy()).isEqualTo(FulcrumDataMigrations.SCHEMA_MIGRATION);
        assertThat(table.canRead("registry-service")).isTrue();
        assertThat(table.canWrite("registry-service")).isTrue();
    }

    private static Set<String> migrationTables() {
        Set<String> tables = new LinkedHashSet<>();
        for (String migration : FulcrumDataMigrations.all()) {
            var matcher = TABLE_TARGET.matcher(readResource(migration));
            while (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        return Set.copyOf(tables);
    }

    private static boolean resourceExists(String resourcePath) {
        return FulcrumSchemaContractTest.class.getClassLoader()
            .getResource(resourcePath) != null;
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = FulcrumSchemaContractTest.class.getClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }

    private static Pattern createTablePattern(String tableName) {
        return Pattern.compile(
            "(?is)\\bCREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+" + Pattern.quote(tableName) + "\\b"
        );
    }
}
