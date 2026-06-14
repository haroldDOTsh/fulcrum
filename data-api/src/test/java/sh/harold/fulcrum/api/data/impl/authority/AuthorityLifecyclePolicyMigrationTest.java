package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityLifecyclePolicyMigrationTest {
    private static final String SCHEMA_MIGRATION = FulcrumDataMigrations.SCHEMA_MIGRATION;

    private static final Map<String, LifecyclePolicy> REQUIRED_POLICIES = requiredPolicies();

    @Test
    void canonicalMigrationListIncludesEveryDataMigration() {
        assertThat(FulcrumDataMigrations.all())
            .containsExactly(SCHEMA_MIGRATION)
            .doesNotHaveDuplicates();

        for (String migration : FulcrumDataMigrations.all()) {
            assertThat(resourceExists(migration))
                .as("migration resource exists: " + migration)
                .isTrue();
        }
    }

    @Test
    void lifecycleMigrationRegistersEveryAppendHeavyTable() {
        String lifecycleSql = readResource(SCHEMA_MIGRATION);
        Map<String, LifecyclePolicy> actual = policyRows(lifecycleSql);

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(REQUIRED_POLICIES.keySet());
        REQUIRED_POLICIES.forEach((table, expected) -> {
            LifecyclePolicy policy = actual.get(table);
            assertThat(policy)
                .as(table + " lifecycle policy")
                .isNotNull();
            assertThat(policy.timestampColumn())
                .as(table + " timestamp column")
                .isEqualTo(expected.timestampColumn());
            assertThat(policy.partitionStrategy())
                .as(table + " partition strategy")
                .isEqualTo(expected.partitionStrategy());
            assertThat(policy.retentionDays())
                .as(table + " retention days")
                .isGreaterThanOrEqualTo(expected.retentionDays());
        });
    }

    @Test
    void lifecyclePoliciesPointAtRealTimestampColumnsAndBrinIndexes() {
        String schemaSql = readSchemaBeforeLifecycleMigration();
        String lifecycleSql = readResource(SCHEMA_MIGRATION);
        String normalizedLifecycleSql = lifecycleSql.replaceAll("\\s+", " ");

        REQUIRED_POLICIES.forEach((table, policy) -> {
            String tableBlock = createTableBlock(schemaSql, table);
            assertThat(tableBlock)
                .as(table + " table block")
                .isNotBlank();
            assertThat(tableBlock)
                .as(table + "." + policy.timestampColumn() + " is a non-null timestamp")
                .containsPattern("(?i)\\b" + policy.timestampColumn() + "\\s+TIMESTAMPTZ\\s+NOT\\s+NULL\\b");
            assertThat(normalizedLifecycleSql)
                .as(table + " BRIN lifecycle index")
                .contains("ON " + table + " USING BRIN (" + policy.timestampColumn() + ")");
        });
    }

    @Test
    void lifecycleMigrationDoesNotPerformLiveRepartitionOrRetentionDeletes() {
        String lifecycleSql = readResource(SCHEMA_MIGRATION).toUpperCase();

        assertThat(lifecycleSql)
            .doesNotContain("PARTITION BY")
            .doesNotContain("PARTITION OF")
            .doesNotContain("DROP TABLE")
            .doesNotContain("TRUNCATE")
            .doesNotContain("DELETE FROM")
            .doesNotContain("CREATE TABLE AS")
            .doesNotContain("INSERT INTO SELECT");
    }

    private static Map<String, LifecyclePolicy> policyRows(String lifecycleSql) {
        Pattern pattern = Pattern.compile(
            "\\('([^']+)',\\s*'([^']+)',\\s*'[^']+',\\s*'([^']+)',\\s*'P1M',\\s*(\\d+),",
            Pattern.MULTILINE
        );
        Map<String, LifecyclePolicy> policies = new LinkedHashMap<>();
        var matcher = pattern.matcher(lifecycleSql);
        while (matcher.find()) {
            policies.put(matcher.group(1), new LifecyclePolicy(
                matcher.group(2),
                matcher.group(3),
                Integer.parseInt(matcher.group(4))
            ));
        }
        return policies;
    }

    private static String readSchemaBeforeLifecycleMigration() {
        return readResource(SCHEMA_MIGRATION);
    }

    private static String createTableBlock(String schemaSql, String tableName) {
        Pattern pattern = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+" + tableName + "\\s*\\((.*?)\\);"
        );
        var matcher = pattern.matcher(schemaSql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean resourceExists(String resourcePath) {
        return AuthorityLifecyclePolicyMigrationTest.class.getClassLoader()
            .getResource(resourcePath) != null;
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityLifecyclePolicyMigrationTest.class.getClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }

    private static Map<String, LifecyclePolicy> requiredPolicies() {
        Map<String, LifecyclePolicy> policies = new LinkedHashMap<>();
        policies.put("authority_commands", monthly("created_at", 90));
        policies.put("authority_command_ingress_log", monthly("received_at", 90));
        policies.put("authority_command_refusal_log", monthly("created_at", 90));
        policies.put("authority_events", monthly("created_at", 90));
        policies.put("authority_event_consumer_failures", monthly("created_at", 90));
        policies.put("authority_projection_checkpoints", monthly("event_created_at", 180));
        policies.put("authority_projection_replay_runs", monthly("created_at", 180));
        policies.put("authority_projection_replay_run_events", monthly("event_created_at", 180));
        policies.put("authority_state_changelog", new LifecyclePolicy("event_created_at", "COMPACTED_CHANGELOG", 3650));
        policies.put("authority_state_restore_runs", monthly("created_at", 180));
        policies.put("authority_idempotency_conflicts", monthly("created_at", 90));
        policies.put("authority_writer_claims", monthly("claimed_at", 90));
        policies.put("player_rank_audit", monthly("created_at", 365));
        policies.put("player_sessions", monthly("started_at", 90));
        policies.put("match_records", monthly("created_at", 180));
        policies.put("match_participant_stats", monthly("created_at", 180));
        policies.put("analytics_events", monthly("created_at", 90));
        return Map.copyOf(policies);
    }

    private static LifecyclePolicy monthly(String timestampColumn, int retentionDays) {
        return new LifecyclePolicy(timestampColumn, "MONTHLY_RANGE", retentionDays);
    }

    private record LifecyclePolicy(String timestampColumn, String partitionStrategy, int retentionDays) {
    }
}
