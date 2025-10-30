package sh.harold.fulcrum.session;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.common.settings.PlayerDebugLevel;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSessionRecordTest {

    @Test
    void debugLevelDefaultsToNone() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        assertThat(record.getDebugLevel()).isEqualTo(PlayerDebugLevel.NONE);
        assertThat(record.isDebugEnabled()).isFalse();
    }

    @Test
    void debugLevelCanBeRaisedAndLowered() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        record.setDebugLevel(PlayerDebugLevel.COUNCIL);
        assertThat(record.getDebugLevel()).isEqualTo(PlayerDebugLevel.COUNCIL);
        assertThat(record.isDebugEnabled()).isTrue();

        record.setDebugLevel(PlayerDebugLevel.NONE);
        assertThat(record.getDebugLevel()).isEqualTo(PlayerDebugLevel.NONE);
        assertThat(record.isDebugEnabled()).isFalse();
    }

    @Test
    void booleanToggleMapsToLevels() {
        PlayerSessionRecord record = PlayerSessionRecord.newSession(UUID.randomUUID(), "session", "server");
        record.setDebugEnabled(true);
        assertThat(record.getDebugLevel()).isEqualTo(PlayerDebugLevel.PLAYER);

        record.setDebugEnabled(false);
        assertThat(record.getDebugLevel()).isEqualTo(PlayerDebugLevel.NONE);
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
