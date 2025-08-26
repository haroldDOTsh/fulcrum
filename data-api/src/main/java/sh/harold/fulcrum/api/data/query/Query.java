package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.Document;
import java.util.List;

/**
 * Query builder interface for filtering and retrieving documents.
 * Provides a fluent API for building complex queries.
 */
public interface Query {
    
    /**
     * Filter documents where the field equals the given value.
     *
     * @param value The value to match
     * @return This query for chaining
     */
    Query equalTo(Object value);
    
    /**
     * Filter documents where the field does not equal the given value.
     * 
     * @param value The value to not match
     * @return This query for chaining
     */
    Query notEquals(Object value);
    
    /**
     * Filter documents where the field contains the given value.
     * For strings, this is a substring match.
     * For arrays, this checks if the array contains the value.
     * 
     * @param value The value to check for
     * @return This query for chaining
     */
    Query contains(Object value);
    
    /**
     * Filter documents where the field is greater than the given value.
     * 
     * @param value The value to compare against
     * @return This query for chaining
     */
    Query greaterThan(Object value);
    
    /**
     * Filter documents where the field is less than the given value.
     * 
     * @param value The value to compare against
     * @return This query for chaining
     */
    Query lessThan(Object value);
    
    /**
     * Filter documents where the field value is in the given list.
     *
     * @param values The list of allowed values
     * @return This query for chaining
     */
    Query in(List<?> values);
    
    /**
     * Filter documents where the field matches a regex pattern.
     *
     * @param regex The regex pattern to match
     * @return This query for chaining
     */
    Query matches(String regex);
    
    /**
     * Filter documents where the field starts with the given prefix.
     *
     * @param prefix The prefix to check for
     * @return This query for chaining
     */
    Query startsWith(String prefix);
    
    /**
     * Filter documents where the field ends with the given suffix.
     *
     * @param suffix The suffix to check for
     * @return This query for chaining
     */
    Query endsWith(String suffix);
    
    /**
     * Filter documents where the field size equals the given value.
     * For strings, this is the length. For arrays, this is the element count.
     *
     * @param size The size to match
     * @return This query for chaining
     */
    Query size(int size);
    
    /**
     * Filter documents where the field is of the given type.
     *
     * @param type The type class to check
     * @return This query for chaining
     */
    Query type(Class<?> type);
    
    /**
     * Apply NOT modifier to the next condition.
     *
     * @return This query for chaining
     */
    Query not();
    
    /**
     * Filter documents where the field is empty.
     * For strings, empty means "". For collections, empty means size 0.
     * For null values, they are considered empty.
     *
     * @return This query for chaining
     */
    Query isEmpty();
    
    /**
     * Filter documents where the field is not empty.
     *
     * @return This query for chaining
     */
    Query isNotEmpty();
    
    /**
     * Add an AND condition with a new field path.
     *
     * @param path The field path for the new condition
     * @return This query for chaining
     */
    Query and(String path);
    
    /**
     * Add an OR condition with a new field path.
     *
     * @param path The field path for the new condition
     * @return This query for chaining
     */
    Query or(String path);
    
    /**
     * Add nested OR conditions using a sub-query.
     *
     * @param subQuery Consumer that builds the sub-query
     * @return This query for chaining
     */
    Query orWhere(java.util.function.Consumer<Query> subQuery);
    
    /**
     * Add nested AND conditions using a sub-query.
     *
     * @param subQuery Consumer that builds the sub-query
     * @return This query for chaining
     */
    Query andWhere(java.util.function.Consumer<Query> subQuery);
    
    /**
     * Filter documents where any of the specified paths match the condition.
     *
     * @param paths The field paths to check
     * @return This query for chaining
     */
    Query whereAny(String... paths);
    
    /**
     * Get distinct values for a specific field.
     *
     * @param path The field path to get distinct values for
     * @return This query for chaining
     */
    Query distinct(String path);
    
    /**
     * Group results by a specific field.
     *
     * @param path The field path to group by
     * @return This query for chaining
     */
    Query groupBy(String path);
    
    /**
     * Filter grouped results (used after groupBy).
     *
     * @param path The field path to filter on
     * @param value The value to match
     * @return This query for chaining
     */
    Query having(String path, Object value);
    
    /**
     * Limit the number of results.
     * 
     * @param limit The maximum number of results
     * @return This query for chaining
     */
    Query limit(int limit);
    
    /**
     * Skip a number of results.
     * 
     * @param skip The number of results to skip
     * @return This query for chaining
     */
    Query skip(int skip);
    
    /**
     * Sort the results by a field.
     * 
     * @param field The field to sort by
     * @param ascending true for ascending order, false for descending
     * @return This query for chaining
     */
    Query sort(String field, boolean ascending);
    
    /**
     * Execute the query and return all matching documents.
     * 
     * @return List of matching documents
     */
    List<Document> execute();
    
    /**
     * Count the number of matching documents without retrieving them.
     * 
     * @return The count of matching documents
     */
    long count();
    
    /**
     * Get the first matching document.
     *
     * @return The first matching document, or null if none found
     */
    Document first();
    
    // Aggregation methods
    
    /**
     * Calculate the sum of a numeric field.
     *
     * @param path The field path to sum
     * @return The sum of the field values
     */
    double sum(String path);
    
    /**
     * Calculate the average of a numeric field.
     *
     * @param path The field path to average
     * @return The average of the field values
     */
    double avg(String path);
    
    /**
     * Get the minimum value of a field.
     *
     * @param path The field path to find minimum
     * @return The minimum value
     */
    Object min(String path);
    
    /**
     * Get the maximum value of a field.
     *
     * @param path The field path to find maximum
     * @return The maximum value
     */
    Object max(String path);
    
    /**
     * Apply a custom aggregation function.
     *
     * @param path The field path to aggregate
     * @param function The aggregation function to apply
     * @return The aggregation result
     */
    Object aggregate(String path, AggregateFunction function);
    
    /**
     * Interface for custom aggregation functions.
     */
    @FunctionalInterface
    interface AggregateFunction {
        /**
         * Apply the aggregation to a list of values.
         *
         * @param values The values to aggregate
         * @return The aggregation result
         */
        Object apply(List<Object> values);
    }
}