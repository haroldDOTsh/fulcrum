package sh.harold.fulcrum.api.data.impl.authority;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds reviewable partition work orders from authority lifecycle metadata.
 */
public final class AuthorityLifecyclePartitionPlanner {
    public static final int DEFAULT_HORIZON_MONTHS = 2;
    public static final String MONTHLY_RANGE = "MONTHLY_RANGE";
    public static final String COMPACTED_CHANGELOG = "COMPACTED_CHANGELOG";
    public static final String MONTH_INTERVAL = "P1M";

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final int MAX_IDENTIFIER_LENGTH = 63;

    private AuthorityLifecyclePartitionPlanner() {
    }

    public static List<PartitionWorkOrder> currentAndNext(Collection<LifecyclePolicy> policies, Clock clock) {
        return plan(policies, clock, DEFAULT_HORIZON_MONTHS);
    }

    public static List<PartitionWorkOrder> plan(
        Collection<LifecyclePolicy> policies,
        Clock clock,
        int horizonMonths
    ) {
        Objects.requireNonNull(policies, "policies");
        if (horizonMonths <= 0) {
            throw new IllegalArgumentException("horizonMonths must be positive");
        }
        YearMonth firstMonth = YearMonth.from(Instant.now(effectiveClock(clock)).atZone(ZoneOffset.UTC));
        List<PartitionWorkOrder> orders = new ArrayList<>();
        for (LifecyclePolicy policy : policies) {
            if (policy.compactedChangelog()) {
                continue;
            }
            if (!policy.monthlyRange()) {
                throw new IllegalArgumentException(
                    "Unsupported lifecycle partition strategy " + policy.partitionStrategy()
                        + " for " + policy.tableName()
                );
            }
            if (!MONTH_INTERVAL.equals(policy.partitionInterval())) {
                throw new IllegalArgumentException(
                    "Unsupported lifecycle partition interval " + policy.partitionInterval()
                        + " for " + policy.tableName()
                );
            }
            for (int monthOffset = 0; monthOffset < horizonMonths; monthOffset++) {
                orders.add(workOrder(policy, firstMonth.plusMonths(monthOffset)));
            }
        }
        orders.sort(Comparator
            .comparing(PartitionWorkOrder::tableName)
            .thenComparing(PartitionWorkOrder::partitionStart));
        return List.copyOf(orders);
    }

    public static List<PartitionInventory> inventory(
        Collection<PartitionWorkOrder> workOrders,
        PartitionCatalog catalog
    ) {
        Objects.requireNonNull(workOrders, "workOrders");
        Objects.requireNonNull(catalog, "catalog");
        List<PartitionInventory> inventory = new ArrayList<>();
        for (PartitionWorkOrder order : workOrders) {
            boolean partitionedRoot = catalog.partitionedRoot(order.tableName(), order.timestampColumn());
            boolean partitionExists = partitionedRoot && catalog.partitionExists(order.partitionName());
            PartitionInventoryStatus status;
            if (!partitionedRoot) {
                status = PartitionInventoryStatus.MISSING_PARTITIONED_ROOT;
            } else if (!partitionExists) {
                status = PartitionInventoryStatus.MISSING_PARTITION;
            } else {
                status = PartitionInventoryStatus.PRESENT;
            }
            inventory.add(new PartitionInventory(order, status));
        }
        return List.copyOf(inventory);
    }

    public static List<Partitionability> partitionability(
        Collection<LifecyclePolicy> policies,
        PartitionConstraintCatalog catalog
    ) {
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(catalog, "catalog");
        List<Partitionability> results = new ArrayList<>();
        for (LifecyclePolicy policy : policies) {
            if (policy.compactedChangelog()) {
                results.add(new Partitionability(
                    policy.tableName(),
                    policy.timestampColumn(),
                    PartitionabilityStatus.METADATA_ONLY,
                    "compacted changelog restore source is lifecycle-managed by log compaction"
                ));
                continue;
            }
            if (!policy.monthlyRange()) {
                results.add(new Partitionability(
                    policy.tableName(),
                    policy.timestampColumn(),
                    PartitionabilityStatus.BLOCKED_BY_UNSUPPORTED_STRATEGY,
                    "unsupported partition strategy " + policy.partitionStrategy()
                ));
                continue;
            }
            if (!catalog.hasTimestampColumn(policy.tableName(), policy.timestampColumn())) {
                results.add(new Partitionability(
                    policy.tableName(),
                    policy.timestampColumn(),
                    PartitionabilityStatus.BLOCKED_BY_MISSING_TIME_KEY,
                    "timestamp column " + policy.timestampColumn() + " is missing"
                ));
                continue;
            }
            List<String> blockers = catalog.uniqueConstraintsMissingColumn(
                policy.tableName(),
                policy.timestampColumn()
            );
            if (!blockers.isEmpty()) {
                results.add(new Partitionability(
                    policy.tableName(),
                    policy.timestampColumn(),
                    PartitionabilityStatus.BLOCKED_BY_GLOBAL_UNIQUENESS,
                    "unique constraints missing partition column: " + String.join(", ", blockers)
                ));
                continue;
            }
            results.add(new Partitionability(
                policy.tableName(),
                policy.timestampColumn(),
                PartitionabilityStatus.PARTITION_READY,
                "all unique constraints include the partition column"
            ));
        }
        return List.copyOf(results);
    }

    public static List<RetentionDryRun> retentionDryRun(
        Collection<LifecyclePolicy> policies,
        Clock clock
    ) {
        Objects.requireNonNull(policies, "policies");
        LocalDate today = Instant.now(effectiveClock(clock)).atZone(ZoneOffset.UTC).toLocalDate();
        List<RetentionDryRun> runs = new ArrayList<>();
        for (LifecyclePolicy policy : policies) {
            if (!policy.monthlyRange()) {
                continue;
            }
            LocalDate retainAfter = today.minusDays(policy.retentionDays());
            YearMonth oldestRetainedMonth = YearMonth.from(retainAfter);
            runs.add(new RetentionDryRun(
                policy.tableName(),
                policy.timestampColumn(),
                retainAfter,
                oldestRetainedMonth,
                policy.archiveBeforeDelete()
                    ? RetentionAction.DETACH_EXPORT_THEN_DROP
                    : RetentionAction.DETACH_THEN_DROP
            ));
        }
        runs.sort(Comparator.comparing(RetentionDryRun::tableName));
        return List.copyOf(runs);
    }

    private static PartitionWorkOrder workOrder(LifecyclePolicy policy, YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.plusMonths(1).atDay(1);
        String partitionName = partitionName(policy.tableName(), month);
        return new PartitionWorkOrder(
            PartitionOperation.CREATE_MONTHLY_PARTITION,
            policy.tableName(),
            partitionName,
            policy.timestampColumn(),
            start,
            end
        );
    }

    private static String partitionName(String tableName, YearMonth month) {
        String name = tableName + "_" + month.getYear() + "_" + twoDigit(month.getMonthValue());
        return requireIdentifier(name, "partitionName");
    }

    private static String twoDigit(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static Clock effectiveClock(Clock clock) {
        return clock == null ? Clock.systemUTC() : clock;
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDENTIFIER_LENGTH || !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a safe PostgreSQL identifier: " + value);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record LifecyclePolicy(
        String tableName,
        String timestampColumn,
        String lifecycleClass,
        String partitionStrategy,
        String partitionInterval,
        int retentionDays,
        boolean archiveBeforeDelete
    ) {
        public LifecyclePolicy {
            tableName = requireIdentifier(tableName, "tableName");
            timestampColumn = requireIdentifier(timestampColumn, "timestampColumn");
            lifecycleClass = requireText(lifecycleClass, "lifecycleClass");
            partitionStrategy = requireText(partitionStrategy, "partitionStrategy");
            partitionInterval = requireText(partitionInterval, "partitionInterval");
            if (retentionDays <= 0) {
                throw new IllegalArgumentException("retentionDays must be positive");
            }
        }

        public static LifecyclePolicy monthly(
            String tableName,
            String timestampColumn,
            String lifecycleClass,
            int retentionDays,
            boolean archiveBeforeDelete
        ) {
            return new LifecyclePolicy(
                tableName,
                timestampColumn,
                lifecycleClass,
                MONTHLY_RANGE,
                MONTH_INTERVAL,
                retentionDays,
                archiveBeforeDelete
            );
        }

        public boolean monthlyRange() {
            return MONTHLY_RANGE.equals(partitionStrategy);
        }

        public boolean compactedChangelog() {
            return COMPACTED_CHANGELOG.equals(partitionStrategy);
        }
    }

    public enum PartitionOperation {
        CREATE_MONTHLY_PARTITION
    }

    public enum PartitionInventoryStatus {
        PRESENT,
        MISSING_PARTITION,
        MISSING_PARTITIONED_ROOT
    }

    public enum PartitionabilityStatus {
        PARTITION_READY,
        BLOCKED_BY_GLOBAL_UNIQUENESS,
        BLOCKED_BY_MISSING_TIME_KEY,
        BLOCKED_BY_UNSUPPORTED_STRATEGY,
        METADATA_ONLY
    }

    public enum RetentionAction {
        DETACH_EXPORT_THEN_DROP,
        DETACH_THEN_DROP
    }

    public interface PartitionCatalog {
        boolean partitionedRoot(String tableName, String timestampColumn);

        boolean partitionExists(String partitionName);
    }

    public interface PartitionConstraintCatalog {
        boolean hasTimestampColumn(String tableName, String timestampColumn);

        List<String> uniqueConstraintsMissingColumn(String tableName, String timestampColumn);
    }

    public static final class PostgresPartitionCatalog implements PartitionCatalog {
        private final Connection connection;

        public PostgresPartitionCatalog(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        @Override
        public boolean partitionedRoot(String tableName, String timestampColumn) {
            requireIdentifier(tableName, "tableName");
            requireIdentifier(timestampColumn, "timestampColumn");
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_class c
                    JOIN pg_partitioned_table pt ON pt.partrelid = c.oid
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(pt.partattrs)
                    WHERE c.relname = ?
                      AND a.attname = ?
                )
                """)) {
                statement.setString(1, tableName);
                statement.setString(2, timestampColumn);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getBoolean(1);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to inspect partitioned root " + tableName, exception);
            }
        }

        @Override
        public boolean partitionExists(String partitionName) {
            requireIdentifier(partitionName, "partitionName");
            try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
                statement.setString(1, partitionName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getBoolean(1);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to inspect partition " + partitionName, exception);
            }
        }
    }

    public record Partitionability(
        String tableName,
        String timestampColumn,
        PartitionabilityStatus status,
        String reason
    ) {
        public Partitionability {
            tableName = requireIdentifier(tableName, "tableName");
            timestampColumn = requireIdentifier(timestampColumn, "timestampColumn");
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
        }
    }

    public record RetentionDryRun(
        String tableName,
        String timestampColumn,
        LocalDate retainAfter,
        YearMonth oldestRetainedMonth,
        RetentionAction action
    ) {
        public RetentionDryRun {
            tableName = requireIdentifier(tableName, "tableName");
            timestampColumn = requireIdentifier(timestampColumn, "timestampColumn");
            retainAfter = Objects.requireNonNull(retainAfter, "retainAfter");
            oldestRetainedMonth = Objects.requireNonNull(oldestRetainedMonth, "oldestRetainedMonth");
            action = Objects.requireNonNull(action, "action");
        }
    }

    public record PartitionInventory(
        PartitionWorkOrder workOrder,
        PartitionInventoryStatus status
    ) {
        public PartitionInventory {
            workOrder = Objects.requireNonNull(workOrder, "workOrder");
            status = Objects.requireNonNull(status, "status");
        }

        public boolean present() {
            return status == PartitionInventoryStatus.PRESENT;
        }
    }

    public record PartitionWorkOrder(
        PartitionOperation operation,
        String tableName,
        String partitionName,
        String timestampColumn,
        LocalDate partitionStart,
        LocalDate partitionEnd
    ) {
        public PartitionWorkOrder {
            operation = Objects.requireNonNull(operation, "operation");
            tableName = requireIdentifier(tableName, "tableName");
            partitionName = requireIdentifier(partitionName, "partitionName");
            timestampColumn = requireIdentifier(timestampColumn, "timestampColumn");
            partitionStart = Objects.requireNonNull(partitionStart, "partitionStart");
            partitionEnd = Objects.requireNonNull(partitionEnd, "partitionEnd");
            if (!partitionEnd.isAfter(partitionStart)) {
                throw new IllegalArgumentException("partitionEnd must be after partitionStart");
            }
        }
    }
}
