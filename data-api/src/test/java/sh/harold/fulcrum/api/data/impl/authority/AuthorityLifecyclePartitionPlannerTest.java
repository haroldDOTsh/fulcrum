package sh.harold.fulcrum.api.data.impl.authority;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.data.impl.postgres.FulcrumDataMigrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorityLifecyclePartitionPlannerTest {
    private static final Clock JUNE_2026 = Clock.fixed(
        Instant.parse("2026-06-15T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void plannerBuildsCurrentAndNextMonthlyPartitionWorkOrdersFromMigrationPolicies() {
        List<AuthorityLifecyclePartitionPlanner.LifecyclePolicy> policies = lifecyclePolicies();

        List<AuthorityLifecyclePartitionPlanner.PartitionWorkOrder> orders =
            AuthorityLifecyclePartitionPlanner.currentAndNext(policies, JUNE_2026);

        assertThat(orders).hasSize((policies.size() - 1) * 2);
        assertThat(orders)
            .extracting(AuthorityLifecyclePartitionPlanner.PartitionWorkOrder::partitionName)
            .contains(
                "authority_commands_2026_06",
                "authority_commands_2026_07",
                "authority_events_2026_06",
                "authority_events_2026_07"
            )
            .doesNotContain("authority_state_changelog_2026_06");
        assertThat(orders)
            .extracting(AuthorityLifecyclePartitionPlanner.PartitionWorkOrder::operation)
            .containsOnly(AuthorityLifecyclePartitionPlanner.PartitionOperation.CREATE_MONTHLY_PARTITION);
    }

    @Test
    void plannerUsesDeterministicPartitionNamesAndMonthlyWindow() {
        AuthorityLifecyclePartitionPlanner.LifecyclePolicy policy =
            AuthorityLifecyclePartitionPlanner.LifecyclePolicy.monthly(
                "authority_commands",
                "created_at",
                "APPEND_AUDIT",
                90,
                true
            );

        List<AuthorityLifecyclePartitionPlanner.PartitionWorkOrder> orders =
            AuthorityLifecyclePartitionPlanner.currentAndNext(List.of(policy), JUNE_2026);

        assertThat(orders).hasSize(2);
        AuthorityLifecyclePartitionPlanner.PartitionWorkOrder june = orders.get(0);
        assertThat(june.operation())
            .isEqualTo(AuthorityLifecyclePartitionPlanner.PartitionOperation.CREATE_MONTHLY_PARTITION);
        assertThat(june.partitionName()).isEqualTo("authority_commands_2026_06");
        assertThat(june.timestampColumn()).isEqualTo("created_at");
        assertThat(june.partitionStart()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(june.partitionEnd()).isEqualTo(LocalDate.parse("2026-07-01"));
    }

    @Test
    void plannerRejectsUnsafeIdentifiersAndUnsupportedIntervals() {
        assertThatThrownBy(() -> AuthorityLifecyclePartitionPlanner.LifecyclePolicy.monthly(
            "authority_commands;drop",
            "created_at",
            "APPEND_AUDIT",
            90,
            true
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("safe PostgreSQL identifier");

        AuthorityLifecyclePartitionPlanner.LifecyclePolicy weeklyPolicy =
            new AuthorityLifecyclePartitionPlanner.LifecyclePolicy(
                "authority_commands",
                "created_at",
                "APPEND_AUDIT",
                AuthorityLifecyclePartitionPlanner.MONTHLY_RANGE,
                "P7D",
                90,
                true
            );
        assertThatThrownBy(() -> AuthorityLifecyclePartitionPlanner.currentAndNext(List.of(weeklyPolicy), JUNE_2026))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported lifecycle partition interval P7D");
    }

    @Test
    void inventoryReportsMissingRootsAndMissingPartitionsWithoutMutating() {
        AuthorityLifecyclePartitionPlanner.LifecyclePolicy policy =
            AuthorityLifecyclePartitionPlanner.LifecyclePolicy.monthly(
                "authority_commands",
                "created_at",
                "APPEND_AUDIT",
                90,
                true
            );
        List<AuthorityLifecyclePartitionPlanner.PartitionWorkOrder> orders =
            AuthorityLifecyclePartitionPlanner.currentAndNext(List.of(policy), JUNE_2026);

        List<AuthorityLifecyclePartitionPlanner.PartitionInventory> missingRoot =
            AuthorityLifecyclePartitionPlanner.inventory(
                orders,
                new FakeCatalog(Set.of(), Set.of())
            );
        List<AuthorityLifecyclePartitionPlanner.PartitionInventory> missingPartition =
            AuthorityLifecyclePartitionPlanner.inventory(
                orders,
                new FakeCatalog(Set.of("authority_commands:created_at"), Set.of("authority_commands_2026_06"))
            );

        assertThat(missingRoot)
            .extracting(AuthorityLifecyclePartitionPlanner.PartitionInventory::status)
            .containsOnly(AuthorityLifecyclePartitionPlanner.PartitionInventoryStatus.MISSING_PARTITIONED_ROOT);
        assertThat(missingPartition)
            .extracting(AuthorityLifecyclePartitionPlanner.PartitionInventory::status)
            .containsExactly(
                AuthorityLifecyclePartitionPlanner.PartitionInventoryStatus.PRESENT,
                AuthorityLifecyclePartitionPlanner.PartitionInventoryStatus.MISSING_PARTITION
            );
        assertThat(missingPartition).extracting(AuthorityLifecyclePartitionPlanner.PartitionInventory::present)
            .containsExactly(true, false);
    }

    @Test
    void partitionabilityClassifiesGlobalUniquenessBlockersAndCompactedMetadataRows() {
        List<AuthorityLifecyclePartitionPlanner.LifecyclePolicy> policies = lifecyclePolicies();

        List<AuthorityLifecyclePartitionPlanner.Partitionability> results =
            AuthorityLifecyclePartitionPlanner.partitionability(
                policies,
                new FakeConstraintCatalog(
                    Set.of(
                        "authority_commands:created_at",
                        "authority_events:created_at",
                        "authority_idempotency_conflicts:created_at",
                        "analytics_events:created_at",
                        "authority_command_ingress_log:received_at",
                        "authority_command_refusal_log:created_at",
                        "authority_state_changelog:event_created_at",
                        "authority_state_restore_runs:created_at"
                    ),
                    Map.of(
                        "authority_commands", List.of("authority_commands_pkey(command_id)", "authority_commands_idempotency_key_key(idempotency_key)"),
                        "authority_events", List.of("authority_events_pkey(event_id)", "uq_authority_events_scope_revision(aggregate_scope, revision)"),
                        "analytics_events", List.of("analytics_events_pkey(event_id)"),
                        "authority_command_ingress_log", List.of("authority_command_ingress_log_pkey(command_id)"),
                        "authority_command_refusal_log", List.of("authority_command_refusal_log_pkey(refusal_id)")
                    )
                )
            );

        assertThat(results)
            .filteredOn(result -> result.tableName().equals("authority_commands"))
            .singleElement()
            .satisfies(result -> {
                assertThat(result.status())
                    .isEqualTo(AuthorityLifecyclePartitionPlanner.PartitionabilityStatus.BLOCKED_BY_GLOBAL_UNIQUENESS);
                assertThat(result.reason()).contains("authority_commands_pkey(command_id)");
            });
        assertThat(results)
            .filteredOn(result -> result.tableName().equals("authority_state_changelog"))
            .singleElement()
            .extracting(AuthorityLifecyclePartitionPlanner.Partitionability::status)
            .isEqualTo(AuthorityLifecyclePartitionPlanner.PartitionabilityStatus.METADATA_ONLY);
        assertThat(results)
            .filteredOn(result -> result.tableName().equals("authority_state_restore_runs"))
            .singleElement()
            .extracting(AuthorityLifecyclePartitionPlanner.Partitionability::status)
            .isEqualTo(AuthorityLifecyclePartitionPlanner.PartitionabilityStatus.PARTITION_READY);
    }

    @Test
    void retentionDryRunComputesOldestRetainedMonthWithoutDeleting() {
        AuthorityLifecyclePartitionPlanner.LifecyclePolicy commands =
            AuthorityLifecyclePartitionPlanner.LifecyclePolicy.monthly(
                "authority_commands",
                "created_at",
                "APPEND_AUDIT",
                90,
                true
            );
        AuthorityLifecyclePartitionPlanner.LifecyclePolicy refusals =
            AuthorityLifecyclePartitionPlanner.LifecyclePolicy.monthly(
                "authority_command_refusal_log",
                "created_at",
                "APPEND_AUDIT",
                30,
                false
            );

        List<AuthorityLifecyclePartitionPlanner.RetentionDryRun> dryRun =
            AuthorityLifecyclePartitionPlanner.retentionDryRun(List.of(commands, refusals), JUNE_2026);

        assertThat(dryRun).hasSize(2);
        assertThat(dryRun)
            .filteredOn(run -> run.tableName().equals("authority_commands"))
            .singleElement()
            .satisfies(run -> {
                assertThat(run.retainAfter()).isEqualTo(LocalDate.parse("2026-03-17"));
                assertThat(run.oldestRetainedMonth()).isEqualTo(YearMonth.parse("2026-03"));
                assertThat(run.action())
                    .isEqualTo(AuthorityLifecyclePartitionPlanner.RetentionAction.DETACH_EXPORT_THEN_DROP);
            });
        assertThat(dryRun)
            .filteredOn(run -> run.tableName().equals("authority_command_refusal_log"))
            .singleElement()
            .extracting(AuthorityLifecyclePartitionPlanner.RetentionDryRun::action)
            .isEqualTo(AuthorityLifecyclePartitionPlanner.RetentionAction.DETACH_THEN_DROP);
    }

    private static List<AuthorityLifecyclePartitionPlanner.LifecyclePolicy> lifecyclePolicies() {
        String migration = readResource(FulcrumDataMigrations.SCHEMA_MIGRATION);
        Pattern pattern = Pattern.compile(
            "\\('([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*(\\d+),\\s*(TRUE|FALSE),",
            Pattern.MULTILINE
        );
        var matcher = pattern.matcher(migration);
        List<AuthorityLifecyclePartitionPlanner.LifecyclePolicy> policies = new ArrayList<>();
        while (matcher.find()) {
            policies.add(new AuthorityLifecyclePartitionPlanner.LifecyclePolicy(
                matcher.group(1),
                matcher.group(2),
                matcher.group(3),
                matcher.group(4),
                matcher.group(5),
                Integer.parseInt(matcher.group(6)),
                Boolean.parseBoolean(matcher.group(7).toLowerCase())
            ));
        }
        return List.copyOf(policies);
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = AuthorityLifecyclePartitionPlannerTest.class.getClassLoader()
            .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, exception);
        }
    }

    private record FakeCatalog(Set<String> partitionedRoots, Set<String> partitions)
        implements AuthorityLifecyclePartitionPlanner.PartitionCatalog {

        @Override
        public boolean partitionedRoot(String tableName, String timestampColumn) {
            return partitionedRoots.contains(tableName + ":" + timestampColumn);
        }

        @Override
        public boolean partitionExists(String partitionName) {
            return partitions.contains(partitionName);
        }
    }

    private record FakeConstraintCatalog(
        Set<String> timestampColumns,
        Map<String, List<String>> uniqueBlockers
    ) implements AuthorityLifecyclePartitionPlanner.PartitionConstraintCatalog {

        @Override
        public boolean hasTimestampColumn(String tableName, String timestampColumn) {
            return timestampColumns.contains(tableName + ":" + timestampColumn);
        }

        @Override
        public List<String> uniqueConstraintsMissingColumn(String tableName, String timestampColumn) {
            return uniqueBlockers.getOrDefault(tableName, List.of());
        }
    }
}
