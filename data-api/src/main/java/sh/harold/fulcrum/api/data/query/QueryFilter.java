package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Represents a filter condition in a cross-schema query.
 * 
 * <p>QueryFilter encapsulates a field-based predicate that can be applied
 * to data from a specific schema. Filters are evaluated during query execution
 * to determine which records should be included in the result set.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class QueryFilter {
    
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
}