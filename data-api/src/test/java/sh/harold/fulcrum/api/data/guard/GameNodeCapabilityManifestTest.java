package sh.harold.fulcrum.api.data.guard;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.AuthorityCommandManifest;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameNodeCapabilityManifestTest {

    @Test
    void loadsNegativeCapabilityManifest() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        assertThat(manifest.version()).isEqualTo(2);
        assertThat(manifest.nodeKind()).isEqualTo(GameNodeStorageGuard.NodeKind.PAPER);
        assertThat(manifest.forbidLocalAuthority()).isTrue();
        assertThat(manifest.forbidDirectStoreConfig()).isTrue();
        assertThat(manifest.forbiddenCapabilities())
            .contains(
                "authority.local",
                "authority.command.message-bus",
                "store.direct.sql",
                "store.migration.resources"
            );
        assertThat(manifest.commandSchemaVersion()).isEqualTo(DataAuthority.COMMAND_SCHEMA_VERSION);
        assertThat(manifest.commandContractFingerprint()).isEqualTo(AuthorityCommandManifest.fingerprint());
        assertThat(manifest.readSchemaVersion()).isEqualTo(DataAuthorityReadContracts.schemaVersion());
        assertThat(manifest.readContractFingerprint()).isEqualTo(DataAuthorityReadContracts.fingerprint());
    }

    @Test
    void rejectsWrongNodeKind() {
        GameNodeCapabilityManifest manifest = manifest("VELOCITY");

        assertThatThrownBy(() -> manifest.requireNodeKind(GameNodeStorageGuard.NodeKind.PAPER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Velocity")
            .hasMessageContaining("Paper");
    }

    @Test
    void rejectsLocalAuthorityModeWhenForbidden() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        assertThatThrownBy(() -> manifest.requireAllowedConfig(Map.of(
            "authority", Map.of("mode", "local")
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.mode=local");
    }

    @Test
    void rejectsDirectStoreConfigWhenForbidden() {
        GameNodeCapabilityManifest manifest = manifest("VELOCITY");

        assertThatThrownBy(() -> manifest.requireAllowedConfig(Map.of(
            "authority", Map.of("mode", "remote"),
            "postgres", Map.of("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum")
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("P3 no-store violation")
            .hasMessageContaining("postgres.jdbc-url");
    }

    @Test
    void rejectsNonDurableAuthorityCommandTransportWhenForbidden() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        assertThatThrownBy(() -> manifest.requireAllowedConfig(Map.of(
            "authority", Map.of("mode", "remote", "command-transport", "message-bus")
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("authority.command-transport=kafka");
    }

    @Test
    void allowsRemoteClientConfigWhenNoStoreSettingsExist() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        manifest.requireAllowedConfig(Map.of(
            "authority", Map.of("mode", "remote", "server-id", "registry-service", "command-transport", "kafka"),
            "redis", Map.of("host", "localhost", "port", 6379)
        ));
    }

    @Test
    void rejectsManifestWithoutForbiddenCapabilities() {
        assertThatThrownBy(() -> GameNodeCapabilityManifest.load(stream("""
            manifest.version=2
            node-kind=PAPER
            forbid-local-authority=true
            forbid-direct-store-config=true
            forbidden-capabilities=
            data-authority.command-schema-version=%d
            data-authority.command-contract-fingerprint=%s
            data-authority.read-schema-version=%d
            data-authority.read-contract-fingerprint=%s
            """.formatted(
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandManifest.fingerprint(),
            DataAuthorityReadContracts.schemaVersion(),
            DataAuthorityReadContracts.fingerprint()
        ))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forbidden-capabilities");
    }

    private static GameNodeCapabilityManifest manifest(String nodeKind) {
        return GameNodeCapabilityManifest.load(stream("""
            manifest.version=2
            node-kind=%s
            forbid-local-authority=true
            forbid-direct-store-config=true
            forbidden-capabilities=authority.local,authority.command.message-bus,store.direct.sql,store.direct.document,store.migration.resources,driver.jdbc.sql,pool.direct.sql
            data-authority.command-schema-version=%d
            data-authority.command-contract-fingerprint=%s
            data-authority.read-schema-version=%d
            data-authority.read-contract-fingerprint=%s
            """.formatted(
            nodeKind,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            AuthorityCommandManifest.fingerprint(),
            DataAuthorityReadContracts.schemaVersion(),
            DataAuthorityReadContracts.fingerprint()
        )));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
