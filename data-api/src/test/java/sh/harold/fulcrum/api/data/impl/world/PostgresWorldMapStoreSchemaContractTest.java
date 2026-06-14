package sh.harold.fulcrum.api.data.impl.world;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;

class PostgresWorldMapStoreSchemaContractTest {

    @Test
    void worldMapsTableIsCoveredByDataApiSchemaContract() {
        PostgresWorldMapStore.validateSchemaContract(FulcrumSchemaContract.loadDefault());
    }
}
