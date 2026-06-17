package sh.harold.fulcrum.distribution.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LobbyAuthoritySchemaProvisionerTest {
    @Test
    void runtimeSchemaProvisioningIsDisabled() {
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> LobbyAuthoritySchemaProvisioner.main(new String[0]));

        assertTrue(exception.getMessage().contains("generated migrations"));
        assertTrue(LobbyAuthoritySchemaProvisioner.disabledReason().contains("service-launcher runtime provisioning"));
    }
}
