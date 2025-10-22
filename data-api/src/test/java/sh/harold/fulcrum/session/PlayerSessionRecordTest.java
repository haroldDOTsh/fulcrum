package sh.harold.fulcrum.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSessionRecordTest {

    @Test
    void debugFlagDefaultsToFalse() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        assertThat(record.isDebugEnabled()).isFalse();
    }

    @Test
    void debugFlagCanBeToggled() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        record.setDebugEnabled(true);
        assertThat(record.isDebugEnabled()).isTrue();

        record.setDebugEnabled(false);
        assertThat(record.isDebugEnabled()).isFalse();
    }

    @Test
    void clientMetadataDefaultsToNull() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        assertThat(record.getClientProtocolVersion()).isNull();
        assertThat(record.getClientBrand()).isNull();
    }

    @Test
    void blankClientBrandClearsValue() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        record.setClientBrand("fabric");
        assertThat(record.getClientBrand()).isEqualTo("fabric");

        record.setClientBrand("   ");
        assertThat(record.getClientBrand()).isNull();
    }
}
