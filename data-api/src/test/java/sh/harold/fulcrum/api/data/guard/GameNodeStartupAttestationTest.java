package sh.harold.fulcrum.api.data.guard;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.authority.DataAuthority;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityCommandContracts;
import sh.harold.fulcrum.api.data.impl.authority.DataAuthorityReadContracts;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class GameNodeStartupAttestationTest {
    @Test
    void reportsStableEvidenceForRemoteGameNodeConfig() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");
        Map<String, Object> config = Map.of(
            "authority", Map.of("mode", "remote", "server-id", "registry-service"),
            "redis", Map.of("host", "localhost", "port", 6379)
        );

        GameNodeStartupAttestation.Report report = GameNodeStartupAttestation.require(
            manifest,
            config,
            emptyLoader(),
            List.of(new GameNodeStartupAttestation.ResourceProbe(
                "driver.jdbc.sql",
                "org/postgresql/Driver.class",
                "postgres driver forbidden"
            ))
        );

        assertThat(report.passed()).isTrue();
        assertThat(report.configFingerprint()).hasSize(64);
        assertThat(report.classpathFingerprint()).hasSize(64);
        assertThat(report.attestationFingerprint()).hasSize(64);
        assertThat(report.summary())
            .contains(
                "nodeKind=Paper",
                "manifestVersion=1",
                "passed=true",
                DataAuthorityCommandContracts.fingerprint().substring(0, 12),
                DataAuthorityReadContracts.fingerprint().substring(0, 12),
                "attestationFingerprint="
            );
        assertThat(report.resourceProbes())
            .extracting(GameNodeStartupAttestation.ResourceProbeResult::present)
            .containsExactly(false);
    }

    @Test
    void rejectsLocalAuthorityInEffectiveConfig() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        assertThatThrownBy(() -> GameNodeStartupAttestation.require(
            manifest,
            Map.of("authority", Map.of("mode", "local")),
            emptyLoader(),
            List.of()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("startup attestation failed")
            .hasMessageContaining("authority.mode");
    }

    @Test
    void rejectsDirectStoreConfigInEffectiveConfig() {
        GameNodeCapabilityManifest manifest = manifest("VELOCITY");

        assertThatThrownBy(() -> GameNodeStartupAttestation.require(
            manifest,
            Map.of(
                "authority", Map.of("mode", "remote"),
                "postgres", Map.of("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum")
            ),
            emptyLoader(),
            List.of()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("postgres.jdbc-url");
    }

    @Test
    void rejectsForbiddenClasspathResourceWhenCapabilityIsManifested() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");
        GameNodeStartupAttestation.ResourceProbe probe = new GameNodeStartupAttestation.ResourceProbe(
            "driver.jdbc.sql",
            "forbidden/Driver.class",
            "driver must be absent"
        );

        GameNodeStartupAttestation.Report report = GameNodeStartupAttestation.inspect(
            manifest,
            Map.of("authority", Map.of("mode", "remote")),
            resourceLoader(Set.of("forbidden/Driver.class")),
            List.of(probe)
        );

        assertThat(report.passed()).isFalse();
        assertThat(report.violations())
            .extracting(GameNodeStartupAttestation.Violation::path)
            .contains("forbidden/Driver.class");
        assertThatThrownBy(() -> GameNodeStartupAttestation.require(
            manifest,
            Map.of("authority", Map.of("mode", "remote")),
            resourceLoader(Set.of("forbidden/Driver.class")),
            List.of(probe)
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forbidden/Driver.class")
            .hasMessageContaining("driver must be absent");
    }

    @Test
    void defaultResourceProbesRejectExternalDocumentAndWideColumnStoreDrivers() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");

        GameNodeStartupAttestation.Report report = GameNodeStartupAttestation.inspect(
            manifest,
            Map.of("authority", Map.of("mode", "remote")),
            resourceLoader(Set.of(
                "com/mongodb/MongoClientSettings.class",
                "com/datastax/oss/driver/api/core/CqlSession.class"
            )),
            GameNodeStartupAttestation.defaultResourceProbes()
        );

        assertThat(report.passed()).isFalse();
        assertThat(report.violations())
            .extracting(GameNodeStartupAttestation.Violation::path)
            .contains(
                "com/mongodb/MongoClientSettings.class",
                "com/datastax/oss/driver/api/core/CqlSession.class"
            );
    }

    @Test
    void rejectsManifestContractFingerprintDrift() {
        GameNodeCapabilityManifest manifest = manifest(
            "PAPER",
            "0000000000000000000000000000000000000000000000000000000000000000",
            DataAuthorityReadContracts.fingerprint()
        );

        assertThatThrownBy(() -> GameNodeStartupAttestation.require(
            manifest,
            Map.of("authority", Map.of("mode", "remote")),
            emptyLoader(),
            List.of()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("data-authority.command-contract-fingerprint")
            .hasMessageContaining(DataAuthorityCommandContracts.fingerprint().substring(0, 12));
    }

    @Test
    void failedAttestationIncludesStableDenialFingerprint() {
        GameNodeCapabilityManifest manifest = manifest("VELOCITY");

        GameNodeStartupAttestation.Report first = GameNodeStartupAttestation.inspect(
            manifest,
            Map.of(
                "authority", Map.of("mode", "remote"),
                "postgres", Map.of("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum")
            ),
            emptyLoader(),
            List.of()
        );
        GameNodeStartupAttestation.Report second = GameNodeStartupAttestation.inspect(
            manifest,
            Map.of(
                "authority", Map.of("mode", "remote"),
                "postgres", Map.of("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum")
            ),
            emptyLoader(),
            List.of()
        );

        assertThat(first.passed()).isFalse();
        assertThat(first.attestationFingerprint()).matches("[0-9a-f]{64}");
        assertThat(first.attestationFingerprint()).isEqualTo(second.attestationFingerprint());

        Throwable thrown = catchThrowable(() -> GameNodeStartupAttestation.require(
            manifest,
            Map.of(
                "authority", Map.of("mode", "remote"),
                "postgres", Map.of("jdbc-url", "jdbc:postgresql://localhost:5432/fulcrum")
            ),
            emptyLoader(),
            List.of()
        ));

        assertThat(thrown)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("denialFingerprint=" + first.attestationFingerprint())
            .hasMessageContaining("postgres.jdbc-url");
    }

    @Test
    void ignoresResourceProbeForCapabilityNotListedInManifest() {
        GameNodeCapabilityManifest manifest = manifest("PAPER");
        GameNodeStartupAttestation.ResourceProbe probe = new GameNodeStartupAttestation.ResourceProbe(
            "store.direct.unlisted",
            "forbidden/Store.class",
            "store must be absent"
        );

        GameNodeStartupAttestation.Report report = GameNodeStartupAttestation.require(
            manifest,
            Map.of("authority", Map.of("mode", "remote")),
            resourceLoader(Set.of("forbidden/Store.class")),
            List.of(probe)
        );

        assertThat(report.passed()).isTrue();
        assertThat(report.resourceProbes()).isEmpty();
    }

    private static GameNodeCapabilityManifest manifest(String nodeKind) {
        return manifest(
            nodeKind,
            DataAuthorityCommandContracts.fingerprint(),
            DataAuthorityReadContracts.fingerprint()
        );
    }

    private static GameNodeCapabilityManifest manifest(
        String nodeKind,
        String commandContractFingerprint,
        String readContractFingerprint
    ) {
        return GameNodeCapabilityManifest.load(new ByteArrayInputStream("""
            manifest.version=1
            node-kind=%s
            forbid-local-authority=true
            forbid-direct-store-config=true
            forbidden-capabilities=authority.local,store.direct.sql,store.direct.document,store.direct.wide-column,store.migration.resources,driver.jdbc.sql,pool.direct.sql
            data-authority.command-schema-version=%d
            data-authority.command-contract-fingerprint=%s
            data-authority.read-schema-version=%d
            data-authority.read-contract-fingerprint=%s
            """.formatted(
            nodeKind,
            DataAuthority.COMMAND_SCHEMA_VERSION,
            commandContractFingerprint,
            DataAuthorityReadContracts.schemaVersion(),
            readContractFingerprint
        ).getBytes(StandardCharsets.UTF_8)));
    }

    private static ClassLoader emptyLoader() {
        return resourceLoader(Set.of());
    }

    private static ClassLoader resourceLoader(Set<String> resources) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                if (!resources.contains(name)) {
                    return null;
                }
                try {
                    return URI.create("file:/forbidden/" + name).toURL();
                } catch (MalformedURLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }
}
