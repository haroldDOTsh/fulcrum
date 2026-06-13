package sh.harold.fulcrum.api.data.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionStorageGuardTest {
    @Test
    void allowsPostgresInProduction() {
        assertThatCode(() -> ProductionStorageGuard.requireProductionSafe(
            StorageType.POSTGRES,
            false,
            "test"
        )).doesNotThrowAnyException();
    }

    @Test
    void allowsJsonAndMongoInDevelopment() {
        assertThatCode(() -> ProductionStorageGuard.requireProductionSafe(
            StorageType.JSON,
            true,
            "test"
        )).doesNotThrowAnyException();

        assertThatCode(() -> ProductionStorageGuard.requireProductionSafe(
            StorageType.MONGODB,
            true,
            "test"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsJsonAndMongoInProduction() {
        assertThatThrownBy(() -> ProductionStorageGuard.requireProductionSafe(
            StorageType.JSON,
            false,
            "test"
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POSTGRES");

        assertThatThrownBy(() -> ProductionStorageGuard.requireProductionSafe(
            StorageType.MONGODB,
            false,
            "test"
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POSTGRES");
    }
}
