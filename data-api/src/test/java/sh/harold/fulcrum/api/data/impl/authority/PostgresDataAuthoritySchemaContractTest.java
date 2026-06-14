package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDataAuthoritySchemaContractTest {

    @Test
    void authorityTablesAreCoveredByDataApiSchemaContract() {
        PostgresDataAuthority.validateSchemaContract(FulcrumSchemaContract.loadDefault());
    }

    @Test
    void authoritySchemaContractRejectsUnknownTables() {
        FulcrumSchemaContract contract = FulcrumSchemaContract.load(new java.io.ByteArrayInputStream("""
            schema.version=1
            tables=player_profiles
            table.player_profiles.ddl-owner=data-api
            table.player_profiles.data-owner=authority-player
            table.player_profiles.created-by=migrations/001_create_fulcrum_data_schema.sql
            table.player_profiles.readers=registry-service
            table.player_profiles.writers=registry-service
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> PostgresDataAuthority.validateSchemaContract(contract))
            .hasMessageContaining("authority_commands");
    }
}
