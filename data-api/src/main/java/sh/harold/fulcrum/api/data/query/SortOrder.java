package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.Objects;

/**
 * Represents a sorting specification for a cross-schema query.
 * 
 * <p>SortOrder defines how results should be ordered based on a specific field
 * from a particular schema. Multiple sort orders can be applied to create
 * complex sorting logic.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class SortOrder {
    /**
     * Compares two CrossSchemaResult objects based on sorting criteria.
     * @param r1 The first result to compare.
     * @param r2 The second result to compare.
     * @return Comparison result as an integer.
     */
    public int compare(CrossSchemaResult r1, CrossSchemaResult r2) {
        // Implement comparison logic based on sorting criteria
        return 0; // Placeholder implementation
    }
    
    private final String fieldName;
    private final Direction direction;
    private final PlayerDataSchema<?> schema;
    private final NullHandling nullHandling;
    
    /**
     * Enum representing sort directions.
     */
    public enum Direction {
        /**
         * Ascending order (smallest to largest, A to Z).
         */
        ASC("ASC"),
        
        /**
         * Descending order (largest to smallest, Z to A).
         */
        DESC("DESC");
        
        private final String sql;
        
        Direction(String sql) {
            this.sql = sql;
        }
        
        public String toSql() {
            return sql;
        }
        
        /**
         * Returns the opposite direction.
         * 
         * @return DESC if current is ASC, ASC if current is DESC
         */
        public Direction reverse() {
            return this == ASC ? DESC : ASC;
        }
    }
    
    /**
     * Enum representing how null values should be handled in sorting.
     */
    public enum NullHandling {
        /**
         * Null values appear first in the sort order.
         */
        NULLS_FIRST("NULLS FIRST"),
        
        /**
         * Null values appear last in the sort order.
         */
        NULLS_LAST("NULLS LAST"),
        
        /**
         * Use database default null handling.
         */
        DEFAULT("");
        
        private final String sql;
        
        NullHandling(String sql) {
            this.sql = sql;
        }
        
        public String toSql() {
            return sql;
        }
    }
    
    /**
     * Creates a new SortOrder with default null handling.
     * 
     * @param fieldName The field to sort by
     * @param direction The sort direction
     * @param schema The schema this field belongs to
     */
    public SortOrder(String fieldName, Direction direction, PlayerDataSchema<?> schema) {
        this(fieldName, direction, schema, NullHandling.DEFAULT);
    }
    
    /**
     * Creates a new SortOrder with specified null handling.
     * 
     * @param fieldName The field to sort by
     * @param direction The sort direction
     * @param schema The schema this field belongs to
     * @param nullHandling How to handle null values
     */
    public SortOrder(String fieldName, Direction direction, PlayerDataSchema<?> schema, NullHandling nullHandling) {
        this.fieldName = Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.direction = Objects.requireNonNull(direction, "Direction cannot be null");
        this.schema = Objects.requireNonNull(schema, "Schema cannot be null");
        this.nullHandling = Objects.requireNonNull(nullHandling, "Null handling cannot be null");
    }
    
    /**
     * Gets the field name to sort by.
     * 
     * @return The field name
     */
    public String getFieldName() {
        return fieldName;
    }
    
    /**
     * Gets the sort direction.
     * 
     * @return The direction (ASC or DESC)
     */
    public Direction getDirection() {
        return direction;
    }
    
    /**
     * Gets the schema this sort order applies to.
     * 
     * @return The schema
     */
    public PlayerDataSchema<?> getSchema() {
        return schema;
    }
    
    /**
     * Gets the null handling strategy.
     * 
     * @return The null handling
     */
    public NullHandling getNullHandling() {
        return nullHandling;
    }
    
    /**
     * Creates a new SortOrder with the direction reversed.
     * 
     * @return A new SortOrder with opposite direction
     */
    public SortOrder reverse() {
        return new SortOrder(fieldName, direction.reverse(), schema, nullHandling);
    }
    
    /**
     * Creates a new SortOrder with different null handling.
     * 
     * @param newNullHandling The new null handling strategy
     * @return A new SortOrder with the specified null handling
     */
    public SortOrder withNullHandling(NullHandling newNullHandling) {
        return new SortOrder(fieldName, direction, schema, newNullHandling);
    }
    
    /**
     * Creates an ascending sort order.
     * 
     * @param fieldName The field to sort by
     * @param schema The schema this field belongs to
     * @return A new ascending SortOrder
     */
    public static SortOrder asc(String fieldName, PlayerDataSchema<?> schema) {
        return new SortOrder(fieldName, Direction.ASC, schema);
    }
    
    /**
     * Creates a descending sort order.
     * 
     * @param fieldName The field to sort by
     * @param schema The schema this field belongs to
     * @return A new descending SortOrder
     */
    public static SortOrder desc(String fieldName, PlayerDataSchema<?> schema) {
        return new SortOrder(fieldName, Direction.DESC, schema);
    }
    
    /**
     * Creates an ascending sort order with null handling.
     * 
     * @param fieldName The field to sort by
     * @param schema The schema this field belongs to
     * @param nullHandling How to handle null values
     * @return A new ascending SortOrder
     */
    public static SortOrder asc(String fieldName, PlayerDataSchema<?> schema, NullHandling nullHandling) {
        return new SortOrder(fieldName, Direction.ASC, schema, nullHandling);
    }
    
    /**
     * Creates a descending sort order with null handling.
     * 
     * @param fieldName The field to sort by
     * @param schema The schema this field belongs to
     * @param nullHandling How to handle null values
     * @return A new descending SortOrder
     */
    public static SortOrder desc(String fieldName, PlayerDataSchema<?> schema, NullHandling nullHandling) {
        return new SortOrder(fieldName, Direction.DESC, schema, nullHandling);
    }
    
    /**
     * Converts this sort order to a SQL ORDER BY clause fragment.
     * Note: This is a simplified version and may need backend-specific adjustments.
     * 
     * @return SQL ORDER BY fragment
     */
    public String toSql() {
        StringBuilder sql = new StringBuilder();
        sql.append(fieldName).append(" ").append(direction.toSql());
        if (nullHandling != NullHandling.DEFAULT) {
            sql.append(" ").append(nullHandling.toSql());
        }
        return sql.toString();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SortOrder sortOrder = (SortOrder) o;
        return Objects.equals(fieldName, sortOrder.fieldName) &&
               direction == sortOrder.direction &&
               Objects.equals(schema, sortOrder.schema) &&
               nullHandling == sortOrder.nullHandling;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fieldName, direction, schema, nullHandling);
    }
    
    @Override
    public String toString() {
        return "SortOrder{" +
               "field='" + fieldName + '\'' +
               ", direction=" + direction +
               ", schema=" + schema.schemaKey() +
               ", nullHandling=" + nullHandling +
               '}';
    }
}