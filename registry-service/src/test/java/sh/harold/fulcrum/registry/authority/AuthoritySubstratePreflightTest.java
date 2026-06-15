package sh.harold.fulcrum.registry.authority;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritySubstratePreflightTest {
    @Test
    void defaultTargetModeRejectsCompatibilityRuntime() {
        AuthoritySubstratePreflight.Report report = AuthoritySubstratePreflight.inspect(Map.of());

        assertThat(report.accepted()).isFalse();
        assertThat(report.targetComplete()).isFalse();
        assertThat(report.mode()).isEqualTo("target");
        assertThat(report.declaredCommandLog()).isEqualTo("kafka");
        assertThat(report.declaredHotState()).isEqualTo("cassandra");
        assertThat(report.violations()).contains(
            "command-log is in-memory, expected kafka",
            "hot-state is in-memory, expected cassandra"
        );
    }

    @Test
    void explicitCompatibilityModeRejectsCleanBreakStartup() {
        AuthoritySubstratePreflight.Report report = AuthoritySubstratePreflight.inspect(Map.of(
            "substrate", Map.of(
                "mode", "compatibility",
                "command-log", "in-memory",
                "hot-state", "in-memory",
                "history", "postgresql",
                "cache", "valkey"
            )
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.targetComplete()).isFalse();
        assertThat(report.mode()).isEqualTo("compatibility");
        assertThat(report.violations()).contains(
            "mode is compatibility, expected target",
            "command-log is in-memory, expected kafka",
            "hot-state is in-memory, expected cassandra"
        );
        assertThat(report.limitations()).contains(
            "command-log is in-memory, expected kafka",
            "hot-state is in-memory, expected cassandra"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mode is compatibility, expected target");
    }

    @Test
    void targetModeAcceptsWhenDeclaredAndActualSubstratesAreTarget() {
        AuthoritySubstratePreflight.Report report = AuthoritySubstratePreflight.inspect(
            Map.of(
                "substrate", Map.of(
                    "mode", "target",
                    "command-log", "kafka",
                    "hot-state", "cassandra",
                    "history", "postgresql",
                    "cache", "valkey"
                )
            ),
            new AuthoritySubstratePreflight.ActualSubstrate(
                "kafka",
                "cassandra",
                "postgresql",
                "valkey"
            )
        );

        assertThat(report.accepted()).isTrue();
        assertThat(report.targetComplete()).isTrue();
        assertThat(report.limitations()).isEmpty();
    }

    @Test
    void targetModeRejectsDeclaredTargetWhenActualRuntimeIsCompatibility() {
        AuthoritySubstratePreflight.Report report = AuthoritySubstratePreflight.inspect(Map.of(
            "substrate", Map.of(
                "mode", "target",
                "command-log", "kafka",
                "hot-state", "cassandra",
                "history", "postgresql",
                "cache", "valkey"
            )
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.targetComplete()).isFalse();
        assertThat(report.violations()).containsExactly(
            "command-log is in-memory, expected kafka",
            "hot-state is in-memory, expected cassandra"
        );
    }

    @Test
    void targetModeRejectsCompatibilitySubstrates() {
        AuthoritySubstratePreflight.Report report = AuthoritySubstratePreflight.inspect(Map.of(
            "substrate", Map.of(
                "mode", "target",
                "command-log", "in-memory",
                "hot-state", "in-memory",
                "history", "postgresql",
                "cache", "valkey"
            )
        ));

        assertThat(report.accepted()).isFalse();
        assertThat(report.violations()).containsExactly(
            "command-log is in-memory, expected kafka",
            "hot-state is in-memory, expected cassandra"
        );
        assertThatThrownBy(report::requireAccepted)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Authority substrate preflight failed");
    }
}
