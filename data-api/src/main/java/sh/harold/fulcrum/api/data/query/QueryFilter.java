package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Represents a filter condition in a cross-schema query with SQL compatibility support.
 *
 * <p>QueryFilter encapsulates a field-based predicate that can be applied
 * to data from a specific schema. Filters are evaluated during query execution
 * to determine which records should be included in the result set.</p>
 *
 * <h2>SQL Compatibility</h2>
 * <p>QueryFilter supports both SQL-compatible and custom predicate-based filtering:</p>
 * <ul>
 *   <li><strong>SQL-Compatible:</strong> Filters created using factory methods (equals, greaterThan, etc.)
 *       can be translated to SQL for efficient database queries</li>
 *   <li><strong>Custom Predicates:</strong> Filters using lambda functions fall back to in-memory filtering</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // SQL-Compatible filters (RECOMMENDED)
 * QueryFilter.equals("functionalRank", "ADMIN", schema)
 * QueryFilter.equals("monthlyPackageRank", "MVP_PLUS_PLUS", schema)
 * QueryFilter.greaterThan("level", 50, schema)
 * QueryFilter.in("status", Arrays.asList("ACTIVE", "PENDING"), schema)
 *
 * // Custom predicate filters (fallback to in-memory)
 * new QueryFilter("field", value -> customLogic(value), schema)
 *
 * // Builder pattern for complex filters
 * QueryFilter.builder("rank", schema)
 *     .equalsValue("MVP")
 *     .or()
 *     .greaterThan(10)
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 * <p>SQL-compatible filters are significantly faster for large datasets as they push
 * filtering to the database level. Custom predicates require loading all data into
 * memory for filtering.</p>
 *
 * <h2>Validation</h2>
 * <p>Use {@link #validateSqlCompatibility(List)} to check filter compatibility before execution.</p>
 *
 * @author Harold
 * @since 1.0
 */
public class QueryFilter {
    /**
     * Converts the filter into an SQL-compatible string.
     * @return SQL representation of the filter.
     */
    public String toSql() {
        if (operator != null) {
            return fieldName + " " + operator.getSymbol() + " " + (value != null ? "'" + value + "'" : "");
        }
        throw new UnsupportedOperationException("Custom predicates cannot be converted to SQL.");
    }
    
    private final String fieldName;
    private final Predicate<?> predicate;
    private final PlayerDataSchema<?> schema;
    private final FilterOperator operator;
    private final Object value;
    
    /**
     * Enum representing common filter operators.
     */
    public enum FilterOperator {
        EQUALS("="),
        NOT_EQUALS("!="),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUAL(">="),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUAL("<="),
        LIKE("LIKE"),
        NOT_LIKE("NOT LIKE"),
        IN("IN"),
        NOT_IN("NOT IN"),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL"),
        BETWEEN("BETWEEN"),
        CONTAINS("CONTAINS"),
        STARTS_WITH("STARTS WITH"),
        ENDS_WITH("ENDS WITH");
        
        private final String symbol;
        
        FilterOperator(String symbol) {
            this.symbol = symbol;
        }
        
        public String getSymbol() {
            return symbol;
        }
    }
    
    /**
     * Creates a new QueryFilter with a custom predicate.
     *
     * @param fieldName The name of the field to filter on
     * @param predicate The predicate to apply
     * @param schema The schema this filter applies to
     */
    public QueryFilter(String fieldName, Predicate<?> predicate, PlayerDataSchema<?> schema) {
        this.fieldName = Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.predicate = Objects.requireNonNull(predicate, "Predicate cannot be null");
        this.schema = Objects.requireNonNull(schema, "Schema cannot be null");
        this.operator = null; // Custom predicate doesn't have a standard operator
        this.value = null;
    }
    
    /**
     * Creates a new QueryFilter with both operator and predicate information.
     * This constructor preserves operator information when copying/recreating filters.
     *
     * @param fieldName The name of the field to filter on
     * @param operator The filter operator (preserved for SQL compatibility)
     * @param value The filter value (preserved for SQL compatibility)
     * @param predicate The predicate to apply
     * @param schema The schema this filter applies to
     */
    public QueryFilter(String fieldName, FilterOperator operator, Object value, Predicate<?> predicate, PlayerDataSchema<?> schema) {
        this.fieldName = Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.operator = operator; // Preserve operator information
        this.value = value; // Preserve value information
        this.predicate = Objects.requireNonNull(predicate, "Predicate cannot be null");
        this.schema = Objects.requireNonNull(schema, "Schema cannot be null");
    }
    
    /**
     * Creates a new QueryFilter with a standard operator and value.
     * 
     * @param fieldName The name of the field to filter on
     * @param operator The filter operator
     * @param value The value to compare against (can be null for IS_NULL/IS_NOT_NULL)
     * @param schema The schema this filter applies to
     */
    public QueryFilter(String fieldName, FilterOperator operator, Object value, PlayerDataSchema<?> schema) {
        this.fieldName = Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.operator = Objects.requireNonNull(operator, "Operator cannot be null");
        this.schema = Objects.requireNonNull(schema, "Schema cannot be null");
        this.value = value;
        this.predicate = createPredicateFromOperator(operator, value);
    }
    
    /**
     * Creates a predicate based on the operator and value.
     */
    @SuppressWarnings("unchecked")
    private Predicate<?> createPredicateFromOperator(FilterOperator operator, Object value) {
        return (Predicate<Object>) obj -> {
            try {
                // Use reflection to get the field value
                var field = obj.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object fieldValue = field.get(obj);
                
                switch (operator) {
                    case EQUALS:
                        return Objects.equals(fieldValue, value);
                    case NOT_EQUALS:
                        return !Objects.equals(fieldValue, value);
                    case IS_NULL:
                        return fieldValue == null;
                    case IS_NOT_NULL:
                        return fieldValue != null;
                    case GREATER_THAN:
                        if (fieldValue instanceof Comparable && value instanceof Comparable) {
                            return ((Comparable) fieldValue).compareTo(value) > 0;
                        }
                        return false;
                    case GREATER_THAN_OR_EQUAL:
                        if (fieldValue instanceof Comparable && value instanceof Comparable) {
                            return ((Comparable) fieldValue).compareTo(value) >= 0;
                        }
                        return false;
                    case LESS_THAN:
                        if (fieldValue instanceof Comparable && value instanceof Comparable) {
                            return ((Comparable) fieldValue).compareTo(value) < 0;
                        }
                        return false;
                    case LESS_THAN_OR_EQUAL:
                        if (fieldValue instanceof Comparable && value instanceof Comparable) {
                            return ((Comparable) fieldValue).compareTo(value) <= 0;
                        }
                        return false;
                    case LIKE:
                    case CONTAINS:
                        if (fieldValue instanceof String && value instanceof String) {
                            return ((String) fieldValue).contains((String) value);
                        }
                        return false;
                    case NOT_LIKE:
                        if (fieldValue instanceof String && value instanceof String) {
                            return !((String) fieldValue).contains((String) value);
                        }
                        return false;
                    case STARTS_WITH:
                        if (fieldValue instanceof String && value instanceof String) {
                            return ((String) fieldValue).startsWith((String) value);
                        }
                        return false;
                    case ENDS_WITH:
                        if (fieldValue instanceof String && value instanceof String) {
                            return ((String) fieldValue).endsWith((String) value);
                        }
                        return false;
                    case IN:
                        if (value instanceof java.util.Collection) {
                            return ((java.util.Collection<?>) value).contains(fieldValue);
                        }
                        return false;
                    case NOT_IN:
                        if (value instanceof java.util.Collection) {
                            return !((java.util.Collection<?>) value).contains(fieldValue);
                        }
                        return false;
                    case BETWEEN:
                        if (value instanceof Object[] && ((Object[]) value).length == 2) {
                            Object[] range = (Object[]) value;
                            if (fieldValue instanceof Comparable && 
                                range[0] instanceof Comparable && 
                                range[1] instanceof Comparable) {
                                return ((Comparable) fieldValue).compareTo(range[0]) >= 0 &&
                                       ((Comparable) fieldValue).compareTo(range[1]) <= 0;
                            }
                        }
                        return false;
                    default:
                        return false;
                }
            } catch (Exception e) {
                // Field not found or inaccessible
                return false;
            }
        };
    }
    
    /**
     * Gets the field name this filter applies to.
     * 
     * @return The field name
     */
    public String getFieldName() {
        return fieldName;
    }
    
    /**
     * Gets the predicate for this filter.
     * 
     * @return The predicate
     */
    public Predicate<?> getPredicate() {
        return predicate;
    }
    
    /**
     * Gets the schema this filter applies to.
     * 
     * @return The schema
     */
    public PlayerDataSchema<?> getSchema() {
        return schema;
    }
    
    /**
     * Gets the filter operator, if available.
     * 
     * @return The operator, or null if using a custom predicate
     */
    public FilterOperator getOperator() {
        return operator;
    }
    
    /**
     * Gets the filter value, if available.
     *
     * @return The value, or null if using a custom predicate or operator doesn't require a value
     */
    public Object getValue() {
        return value;
    }
    
    /**
     * Determines if this filter can be translated to SQL.
     *
     * @return true if this filter has operator information and can be translated to SQL, false otherwise
     */
    public boolean isSqlCompatible() {
        return operator != null;
    }
    
    /**
     * Validates a list of filters for SQL compatibility and returns detailed diagnostics.
     *
     * @param filters The list of filters to validate
     * @return A ValidationResult containing compatibility status and detailed diagnostics
     */
    public static ValidationResult validateSqlCompatibility(List<QueryFilter> filters) {
        ValidationResult result = new ValidationResult();
        
        for (QueryFilter filter : filters) {
            if (!filter.isSqlCompatible()) {
                result.addIncompatibleFilter(filter);
            } else {
                result.addCompatibleFilter(filter);
            }
        }
        
        return result;
    }
    
    /**
     * Gets a human-readable explanation of why this filter is not SQL-compatible.
     *
     * @return A string explaining the compatibility issue, or null if the filter is compatible
     */
    public String getIncompatibilityReason() {
        if (isSqlCompatible()) {
            return null;
        }
        
        if (operator == null) {
            return "Filter uses a custom predicate function instead of a standard operator. " +
                   "Use QueryFilter factory methods (equals, greaterThan, etc.) to create SQL-compatible filters.";
        }
        
        return "Unknown compatibility issue";
    }
    
    /**
     * Suggests how to fix a non-SQL-compatible filter.
     *
     * @return A string with suggested fixes, or null if the filter is already compatible
     */
    public String getSqlCompatibilityFix() {
        if (isSqlCompatible()) {
            return null;
        }
        
        if (operator == null) {
            return String.format("Replace custom predicate with QueryFilter.equals(\"%s\", value, schema) " +
                                "or other factory methods like greaterThan, lessThan, like, etc.", fieldName);
        }
        
        return "Use QueryFilter factory methods to create SQL-compatible filters";
    }
    
    /**
     * Applies this filter to an object.
     * 
     * @param obj The object to test
     * @return true if the object passes the filter, false otherwise
     */
    @SuppressWarnings("unchecked")
    public boolean test(Object obj) {
        return ((Predicate<Object>) predicate).test(obj);
    }
    
    /**
     * Creates a new EQUALS filter.
     */
    public static QueryFilter equals(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.EQUALS, value, schema);
    }
    
    /**
     * Creates a new NOT_EQUALS filter.
     */
    public static QueryFilter notEquals(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.NOT_EQUALS, value, schema);
    }
    
    /**
     * Creates a new GREATER_THAN filter.
     */
    public static QueryFilter greaterThan(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.GREATER_THAN, value, schema);
    }
    
    /**
     * Creates a new LESS_THAN filter.
     */
    public static QueryFilter lessThan(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.LESS_THAN, value, schema);
    }
    
    /**
     * Creates a new LIKE filter.
     */
    public static QueryFilter like(String fieldName, String pattern, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.LIKE, pattern, schema);
    }
    
    /**
     * Creates a new IN filter.
     */
    public static QueryFilter in(String fieldName, java.util.Collection<?> values, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.IN, values, schema);
    }
    
    /**
     * Creates a new IS_NULL filter.
     */
    public static QueryFilter isNull(String fieldName, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.IS_NULL, null, schema);
    }
    
    /**
     * Creates a new IS_NOT_NULL filter.
     */
    public static QueryFilter isNotNull(String fieldName, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.IS_NOT_NULL, null, schema);
    }
    
    /**
     * Creates a new BETWEEN filter.
     */
    public static QueryFilter between(String fieldName, Object minValue, Object maxValue, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.BETWEEN, new Object[]{minValue, maxValue}, schema);
    }
    
    // ===== ENHANCED FACTORY METHODS FOR BETTER USABILITY =====
    
    /**
     * Creates an EQUALS filter with enum support.
     * Automatically converts enum values to their string representation.
     */
    public static QueryFilter equalsEnum(String fieldName, Enum<?> enumValue, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.EQUALS, enumValue != null ? enumValue.name() : null, schema);
    }
    
    /**
     * Creates an EQUALS filter with automatic null handling.
     * Converts null values to IS_NULL operator.
     */
    public static QueryFilter equalsOrNull(String fieldName, Object value, PlayerDataSchema<?> schema) {
        if (value == null) {
            return isNull(fieldName, schema);
        }
        return equals(fieldName, value, schema);
    }
    
    /**
     * Creates an IN filter for enum values.
     * Automatically converts enum values to their string representations.
     */
    public static QueryFilter inEnum(String fieldName, java.util.Collection<? extends Enum<?>> enumValues, PlayerDataSchema<?> schema) {
        List<String> stringValues = enumValues.stream()
            .map(e -> e != null ? e.name() : null)
            .collect(java.util.stream.Collectors.toList());
        return in(fieldName, stringValues, schema);
    }
    
    /**
     * Creates a CONTAINS filter for string fields.
     * Convenience method for substring matching.
     */
    public static QueryFilter contains(String fieldName, String substring, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.CONTAINS, substring, schema);
    }
    
    /**
     * Creates a STARTS_WITH filter for string fields.
     */
    public static QueryFilter startsWith(String fieldName, String prefix, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.STARTS_WITH, prefix, schema);
    }
    
    /**
     * Creates an ENDS_WITH filter for string fields.
     */
    public static QueryFilter endsWith(String fieldName, String suffix, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.ENDS_WITH, suffix, schema);
    }
    
    /**
     * Creates a GREATER_THAN_OR_EQUAL filter.
     */
    public static QueryFilter greaterThanOrEqual(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.GREATER_THAN_OR_EQUAL, value, schema);
    }
    
    /**
     * Creates a LESS_THAN_OR_EQUAL filter.
     */
    public static QueryFilter lessThanOrEqual(String fieldName, Object value, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.LESS_THAN_OR_EQUAL, value, schema);
    }
    
    /**
     * Creates a NOT_IN filter.
     */
    public static QueryFilter notIn(String fieldName, java.util.Collection<?> values, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.NOT_IN, values, schema);
    }
    
    /**
     * Creates a NOT_LIKE filter.
     */
    public static QueryFilter notLike(String fieldName, String pattern, PlayerDataSchema<?> schema) {
        return new QueryFilter(fieldName, FilterOperator.NOT_LIKE, pattern, schema);
    }
    
    // ===== BUILDER PATTERN FOR COMPLEX FILTERS =====
    
    /**
     * Creates a filter builder for more complex filter construction.
     */
    public static FilterBuilder builder(String fieldName, PlayerDataSchema<?> schema) {
        return new FilterBuilder(fieldName, schema);
    }
    
    /**
     * Builder class for creating complex filters with a fluent API.
     */
    public static class FilterBuilder {
        private final String fieldName;
        private final PlayerDataSchema<?> schema;
        
        FilterBuilder(String fieldName, PlayerDataSchema<?> schema) {
            this.fieldName = fieldName;
            this.schema = schema;
        }
        
        public QueryFilter equalsValue(Object value) {
            return QueryFilter.equals(fieldName, value, schema);
        }
        
        public QueryFilter equalsEnum(Enum<?> enumValue) {
            return QueryFilter.equalsEnum(fieldName, enumValue, schema);
        }
        
        public QueryFilter notEquals(Object value) {
            return QueryFilter.notEquals(fieldName, value, schema);
        }
        
        public QueryFilter greaterThan(Object value) {
            return QueryFilter.greaterThan(fieldName, value, schema);
        }
        
        public QueryFilter lessThan(Object value) {
            return QueryFilter.lessThan(fieldName, value, schema);
        }
        
        public QueryFilter greaterThanOrEqual(Object value) {
            return QueryFilter.greaterThanOrEqual(fieldName, value, schema);
        }
        
        public QueryFilter lessThanOrEqual(Object value) {
            return QueryFilter.lessThanOrEqual(fieldName, value, schema);
        }
        
        public QueryFilter like(String pattern) {
            return QueryFilter.like(fieldName, pattern, schema);
        }
        
        public QueryFilter contains(String substring) {
            return QueryFilter.contains(fieldName, substring, schema);
        }
        
        public QueryFilter startsWith(String prefix) {
            return QueryFilter.startsWith(fieldName, prefix, schema);
        }
        
        public QueryFilter endsWith(String suffix) {
            return QueryFilter.endsWith(fieldName, suffix, schema);
        }
        
        public QueryFilter in(java.util.Collection<?> values) {
            return QueryFilter.in(fieldName, values, schema);
        }
        
        public QueryFilter notIn(java.util.Collection<?> values) {
            return QueryFilter.notIn(fieldName, values, schema);
        }
        
        public QueryFilter inEnum(java.util.Collection<? extends Enum<?>> enumValues) {
            return QueryFilter.inEnum(fieldName, enumValues, schema);
        }
        
        public QueryFilter isNull() {
            return QueryFilter.isNull(fieldName, schema);
        }
        
        public QueryFilter isNotNull() {
            return QueryFilter.isNotNull(fieldName, schema);
        }
        
        public QueryFilter between(Object minValue, Object maxValue) {
            return QueryFilter.between(fieldName, minValue, maxValue, schema);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryFilter that = (QueryFilter) o;
        return Objects.equals(fieldName, that.fieldName) &&
               Objects.equals(schema, that.schema) &&
               operator == that.operator &&
               Objects.equals(value, that.value);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fieldName, schema, operator, value);
    }
    
    @Override
    public String toString() {
        if (operator != null) {
            return "QueryFilter{" +
                   "field='" + fieldName + '\'' +
                   ", operator=" + operator +
                   ", value=" + value +
                   ", schema=" + schema.schemaKey() +
                   '}';
        } else {
            return "QueryFilter{" +
                   "field='" + fieldName + '\'' +
                   ", predicate=custom" +
                   ", schema=" + schema.schemaKey() +
                   '}';
        }
    }
    
    /**
     * Result of SQL compatibility validation for a set of filters.
     */
    public static class ValidationResult {
        private final List<QueryFilter> compatibleFilters = new ArrayList<>();
        private final List<QueryFilter> incompatibleFilters = new ArrayList<>();
        
        /**
         * Adds a compatible filter to the result.
         */
        void addCompatibleFilter(QueryFilter filter) {
            compatibleFilters.add(filter);
        }
        
        /**
         * Adds an incompatible filter to the result.
         */
        void addIncompatibleFilter(QueryFilter filter) {
            incompatibleFilters.add(filter);
        }
        
        /**
         * @return true if all filters are SQL-compatible
         */
        public boolean isFullyCompatible() {
            return incompatibleFilters.isEmpty();
        }
        
        /**
         * @return true if no filters are SQL-compatible
         */
        public boolean isFullyIncompatible() {
            return compatibleFilters.isEmpty();
        }
        
        /**
         * @return List of SQL-compatible filters
         */
        public List<QueryFilter> getCompatibleFilters() {
            return new ArrayList<>(compatibleFilters);
        }
        
        /**
         * @return List of non-SQL-compatible filters
         */
        public List<QueryFilter> getIncompatibleFilters() {
            return new ArrayList<>(incompatibleFilters);
        }
        
        /**
         * @return Count of compatible filters
         */
        public int getCompatibleCount() {
            return compatibleFilters.size();
        }
        
        /**
         * @return Count of incompatible filters
         */
        public int getIncompatibleCount() {
            return incompatibleFilters.size();
        }
        
        /**
         * @return Total count of filters
         */
        public int getTotalCount() {
            return compatibleFilters.size() + incompatibleFilters.size();
        }
        
        /**
         * @return A detailed diagnostic report
         */
        public String getDiagnosticReport() {
            StringBuilder report = new StringBuilder();
            report.append("SQL Compatibility Report:\n");
            report.append("  Total filters: ").append(getTotalCount()).append("\n");
            report.append("  Compatible: ").append(getCompatibleCount()).append("\n");
            report.append("  Incompatible: ").append(getIncompatibleCount()).append("\n\n");
            
            if (!incompatibleFilters.isEmpty()) {
                report.append("Incompatible filters:\n");
                for (QueryFilter filter : incompatibleFilters) {
                    report.append("  - ").append(filter.toString()).append("\n");
                    report.append("    Reason: ").append(filter.getIncompatibilityReason()).append("\n");
                    report.append("    Fix: ").append(filter.getSqlCompatibilityFix()).append("\n\n");
                }
            }
            
            return report.toString();
        }
    }
}