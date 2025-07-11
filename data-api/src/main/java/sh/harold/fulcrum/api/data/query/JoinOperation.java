package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.*;

/**
 * Represents a join operation between two schemas in a cross-schema query.
 * 
 * <p>This class encapsulates the information needed to perform a join between
 * schemas, including the target schema and any filters that should be applied
 * to the joined data.</p>
 * 
 * <p>Joins are always performed using UUID as the common key between schemas.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class JoinOperation {
    
    private final PlayerDataSchema<?> targetSchema;
    private final List<QueryFilter> filters;
    private final JoinType joinType;
    
    /**
     * Defines the type of join operation.
     */
    public enum JoinType {
        /**
         * Inner join - only includes results where data exists in both schemas.
         */
        INNER,
        
        /**
         * Left join - includes all results from the left schema, with null values
         * for missing data in the right schema.
         */
        LEFT,
        
        /**
         * Right join - includes all results from the right schema, with null values
         * for missing data in the left schema.
         */
        RIGHT,
        
        /**
         * Full outer join - includes all results from both schemas, with null values
         * for missing data on either side.
         */
        FULL
    }
    
    /**
     * Creates a new join operation with the specified target schema and filters.
     * Uses INNER join by default.
     * 
     * @param targetSchema The schema to join with
     * @param filters The filters to apply to the joined data
     */
    public JoinOperation(PlayerDataSchema<?> targetSchema, List<QueryFilter> filters) {
        this(targetSchema, filters, JoinType.INNER);
    }
    
    /**
     * Creates a new join operation with the specified target schema, filters, and join type.
     * 
     * @param targetSchema The schema to join with
     * @param filters The filters to apply to the joined data
     * @param joinType The type of join to perform
     */
    public JoinOperation(PlayerDataSchema<?> targetSchema, List<QueryFilter> filters, JoinType joinType) {
        this.targetSchema = Objects.requireNonNull(targetSchema, "Target schema cannot be null");
        this.filters = new ArrayList<>(Objects.requireNonNull(filters, "Filters cannot be null"));
        this.joinType = Objects.requireNonNull(joinType, "Join type cannot be null");
    }
    
    /**
     * Gets the target schema for this join operation.
     * 
     * @return The target schema
     */
    public PlayerDataSchema<?> getTargetSchema() {
        return targetSchema;
    }
    
    /**
     * Gets the filters to apply to the joined data.
     * 
     * @return An unmodifiable list of filters
     */
    public List<QueryFilter> getFilters() {
        return Collections.unmodifiableList(filters);
    }
    
    /**
     * Gets the type of join operation.
     * 
     * @return The join type
     */
    public JoinType getJoinType() {
        return joinType;
    }
    
    /**
     * Checks if this join has any filters applied.
     * 
     * @return true if filters are present, false otherwise
     */
    public boolean hasFilters() {
        return !filters.isEmpty();
    }
    
    /**
     * Gets the schema key for the target schema.
     * 
     * @return The schema key
     */
    public String getSchemaKey() {
        return targetSchema.schemaKey();
    }
    
    /**
     * Creates a new JoinOperation with an additional filter.
     * 
     * @param filter The filter to add
     * @return A new JoinOperation with the added filter
     */
    public JoinOperation withFilter(QueryFilter filter) {
        List<QueryFilter> newFilters = new ArrayList<>(filters);
        newFilters.add(filter);
        return new JoinOperation(targetSchema, newFilters, joinType);
    }
    
    /**
     * Creates a new JoinOperation with a different join type.
     * 
     * @param newJoinType The new join type
     * @return A new JoinOperation with the specified join type
     */
    public JoinOperation withJoinType(JoinType newJoinType) {
        return new JoinOperation(targetSchema, filters, newJoinType);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JoinOperation that = (JoinOperation) o;
        return Objects.equals(targetSchema, that.targetSchema) &&
               Objects.equals(filters, that.filters) &&
               joinType == that.joinType;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(targetSchema, filters, joinType);
    }
    
    @Override
    public String toString() {
        return "JoinOperation{" +
               "targetSchema=" + targetSchema.schemaKey() +
               ", filters=" + filters.size() +
               ", joinType=" + joinType +
               '}';
    }
}