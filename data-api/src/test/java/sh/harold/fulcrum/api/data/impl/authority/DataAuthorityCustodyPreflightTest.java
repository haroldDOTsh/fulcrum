package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAuthorityCustodyPreflightTest {
    @Test
    void reportCarriesStableCustodyEvidence() {
        DataAuthorityCustodyPreflight.Report first = DataAuthorityCustodyPreflight.require(
            "registry-service",
            "message-bus-provider",
            List.of(
                DataAuthorityCustodyPreflight.check("partition-epoch-schema", () -> {
                }),
                DataAuthorityCustodyPreflight.check("authority-command-log-schema", () -> {
                })
            )
        );
        DataAuthorityCustodyPreflight.Report second = DataAuthorityCustodyPreflight.require(
            "registry-service",
            "message-bus-provider",
            List.of(
                DataAuthorityCustodyPreflight.check("authority-command-log-schema", () -> {
                }),
                DataAuthorityCustodyPreflight.check("partition-epoch-schema", () -> {
                })
            )
        );

        assertThat(first.passed()).isTrue();
        assertThat(first.commandContractFingerprint()).isEqualTo(DataAuthorityCommandContracts.fingerprint());
        assertThat(first.readContractFingerprint()).isEqualTo(DataAuthorityReadContracts.fingerprint());
        assertThat(first.custodyFingerprint()).matches("[0-9a-f]{64}");
        assertThat(first.custodyFingerprint()).isEqualTo(second.custodyFingerprint());
        assertThat(first.summary())
            .contains(
                "ownerNode=registry-service",
                "principalSource=message-bus-provider",
                "passed=true",
                "custodyFingerprint="
            );
        assertThat(first.checks())
            .extracting(DataAuthorityCustodyPreflight.CheckResult::name)
            .containsExactly("authority-command-log-schema", "partition-epoch-schema");
    }

    @Test
    void requireRejectsWithExactFailedCheckEvidence() {
        AtomicInteger successfulChecks = new AtomicInteger();

        assertThatThrownBy(() -> DataAuthorityCustodyPreflight.require(
            "registry-service",
            "message-bus-provider",
            List.of(
                DataAuthorityCustodyPreflight.check("postgres-data-authority-schema",
                    () -> successfulChecks.incrementAndGet()),
                DataAuthorityCustodyPreflight.check("authority-partition-epoch-schema",
                    () -> {
                        throw new IllegalStateException("missing authority_partition_epochs");
                    })
            )
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Data Authority custody preflight failed")
            .hasMessageContaining("custodyFingerprint=")
            .hasMessageContaining("authority-partition-epoch-schema")
            .hasMessageContaining("missing authority_partition_epochs");

        assertThat(successfulChecks).hasValue(1);
    }
}
