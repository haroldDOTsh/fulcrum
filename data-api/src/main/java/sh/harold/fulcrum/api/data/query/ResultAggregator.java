package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Aggregates and combines data from multiple schemas in cross-schema queries.
 * 
 * <p>This class provides functionality for:</p>
 * <ul>
 *   <li>Merging data from different schemas for the same player</li>
 *   <li>Performing aggregation operations (count, sum, avg, etc.)</li>
 *   <li>Grouping results by specified fields</li>
 *   <li>Computing derived fields from multiple schemas</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class ResultAggregator {
    
    /**
     * Aggregation functions that can be applied to numeric fields.
     */
    public enum AggregationFunction {
        COUNT,
        SUM,
        AVG,
        MIN,
        MAX,
        FIRST,
        LAST,
        COLLECT_LIST,
        COLLECT_SET
    }
    
    /**
     * Represents an aggregation operation on a specific field.
     */
    public static class AggregationSpec {
        private final String fieldName;
        private final PlayerDataSchema<?> schema;
        private final AggregationFunction function;
        private final String outputName;
        
        public AggregationSpec(String fieldName, PlayerDataSchema<?> schema, 
                              AggregationFunction function, String outputName) {
            this.fieldName = Objects.requireNonNull(fieldName);
            this.schema = Objects.requireNonNull(schema);
            this.function = Objects.requireNonNull(function);
            this.outputName = Objects.requireNonNull(outputName);
        }
        
        // Getters
        public String getFieldName() { return fieldName; }
        public PlayerDataSchema<?> getSchema() { return schema; }
        public AggregationFunction getFunction() { return function; }
        public String getOutputName() { return outputName; }
    }
    
    /**
     * Groups results by specified fields and applies aggregations.
     * 
     * @param results The results to aggregate
     * @param groupByFields Fields to group by (schema -> field names)
     * @param aggregations Aggregation specifications
     * @return Map of group keys to aggregated results
     */
    public Map<Map<String, Object>, Map<String, Object>> groupAndAggregate(
            List<CrossSchemaResult> results,
            Map<PlayerDataSchema<?>, List<String>> groupByFields,
            List<AggregationSpec> aggregations) {
        
        // Group results
        Map<Map<String, Object>, List<CrossSchemaResult>> groups = results.stream()
            .collect(Collectors.groupingBy(result -> extractGroupKey(result, groupByFields)));
        
        // Apply aggregations to each group
        Map<Map<String, Object>, Map<String, Object>> aggregatedResults = new HashMap<>();
        
        for (Map.Entry<Map<String, Object>, List<CrossSchemaResult>> group : groups.entrySet()) {
            Map<String, Object> groupKey = group.getKey();
            List<CrossSchemaResult> groupResults = group.getValue();
            
            Map<String, Object> aggregatedValues = new HashMap<>();
            for (AggregationSpec spec : aggregations) {
                Object aggregatedValue = applyAggregation(groupResults, spec);
                aggregatedValues.put(spec.getOutputName(), aggregatedValue);
            }
            
            aggregatedResults.put(groupKey, aggregatedValues);
        }
        
        return aggregatedResults;
    }
    
    /**
     * Computes aggregate statistics for a set of results.
     * 
     * @param results The results to analyze
     * @param fieldName The field to compute statistics for
     * @param schema The schema containing the field
     * @return Map of statistic names to values
     */
    public Map<String, Number> computeStatistics(List<CrossSchemaResult> results, 
                                                 String fieldName, 
                                                 PlayerDataSchema<?> schema) {
        List<Number> values = results.stream()
            .map(r -> r.getField(schema, fieldName))
            .filter(Objects::nonNull)
            .filter(Number.class::isInstance)
            .map(Number.class::cast)
            .collect(Collectors.toList());
        
        if (values.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Number> stats = new HashMap<>();
        
        // Count
        stats.put("count", values.size());
        
        // Sum
        double sum = values.stream().mapToDouble(Number::doubleValue).sum();
        stats.put("sum", sum);
        
        // Average
        stats.put("avg", sum / values.size());
        
        // Min/Max
        double min = values.stream().mapToDouble(Number::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Number::doubleValue).max().orElse(0);
        stats.put("min", min);
        stats.put("max", max);
        
        // Standard deviation
        double avg = sum / values.size();
        double variance = values.stream()
            .mapToDouble(Number::doubleValue)
            .map(v -> Math.pow(v - avg, 2))
            .average()
            .orElse(0);
        stats.put("stddev", Math.sqrt(variance));
        
        return stats;
    }
    
    /**
     * Merges multiple results for the same player into a single result.
     * 
     * @param results Results to merge (should all have the same UUID)
     * @param mergeStrategy Strategy for handling conflicts
     * @return Merged result
     */
    public CrossSchemaResult mergeResults(List<CrossSchemaResult> results, 
                                         BinaryOperator<Object> mergeStrategy) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge empty result list");
        }
        
        UUID uuid = results.get(0).getPlayerUuid();
        CrossSchemaResult merged = new CrossSchemaResult(uuid);
        
        // Collect all schemas
        Set<PlayerDataSchema<?>> allSchemas = results.stream()
            .flatMap(r -> r.getSchemas().stream())
            .collect(Collectors.toSet());
        
        // Merge data for each schema
        for (PlayerDataSchema<?> schema : allSchemas) {
            List<Object> dataObjects = results.stream()
                .map(r -> r.getData(schema))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            if (!dataObjects.isEmpty()) {
                Object mergedData = dataObjects.stream()
                    .reduce(mergeStrategy)
                    .orElse(null);
                
                if (mergedData != null) {
                    addSchemaDataUnchecked(merged, schema, mergedData);
                }
            }
        }
        
        return merged;
    }
    
    /**
     * Creates derived fields based on data from multiple schemas.
     * 
     * @param result The result to enhance
     * @param derivedFields Map of field names to computation functions
     * @return New result with derived fields added
     */
    public CrossSchemaResult addDerivedFields(CrossSchemaResult result, 
                                             Map<String, Function<CrossSchemaResult, Object>> derivedFields) {
        // Create a new result with the same data
        CrossSchemaResult enhanced = new CrossSchemaResult(result.getPlayerUuid());
        
        // Copy existing data
        for (PlayerDataSchema<?> schema : result.getSchemas()) {
            Object data = result.getData(schema);
            if (data != null) {
                addSchemaDataUnchecked(enhanced, schema, data);
            }
        }
        
        // Add derived fields as a synthetic schema
        Map<String, Object> derivedData = new HashMap<>();
        for (Map.Entry<String, Function<CrossSchemaResult, Object>> entry : derivedFields.entrySet()) {
            String fieldName = entry.getKey();
            Function<CrossSchemaResult, Object> computation = entry.getValue();
            
            try {
                Object value = computation.apply(result);
                derivedData.put(fieldName, value);
            } catch (Exception e) {
                // Log error but continue with other fields
                derivedData.put(fieldName, null);
            }
        }
        
        // Would add derived data to the result if we had a way to represent it
        // For now, this is a placeholder for the concept
        
        return enhanced;
    }
    
    /**
     * Performs a pivot operation on results.
     * 
     * @param results The results to pivot
     * @param rowSchema Schema for row grouping
     * @param rowField Field for row grouping
     * @param columnSchema Schema for column grouping
     * @param columnField Field for column grouping
     * @param valueSchema Schema for values
     * @param valueField Field for values
     * @param aggregation Aggregation function for values
     * @return Pivoted data as a map
     */
    public Map<Object, Map<Object, Object>> pivot(
            List<CrossSchemaResult> results,
            PlayerDataSchema<?> rowSchema, String rowField,
            PlayerDataSchema<?> columnSchema, String columnField,
            PlayerDataSchema<?> valueSchema, String valueField,
            AggregationFunction aggregation) {
        
        Map<Object, Map<Object, List<Object>>> pivotData = new HashMap<>();
        
        // Collect data
        for (CrossSchemaResult result : results) {
            Object rowValue = result.getField(rowSchema, rowField);
            Object columnValue = result.getField(columnSchema, columnField);
            Object value = result.getField(valueSchema, valueField);
            
            if (rowValue != null && columnValue != null && value != null) {
                pivotData.computeIfAbsent(rowValue, k -> new HashMap<>())
                         .computeIfAbsent(columnValue, k -> new ArrayList<>())
                         .add(value);
            }
        }
        
        // Apply aggregation
        Map<Object, Map<Object, Object>> pivotResult = new HashMap<>();
        for (Map.Entry<Object, Map<Object, List<Object>>> rowEntry : pivotData.entrySet()) {
            Map<Object, Object> columnResults = new HashMap<>();
            
            for (Map.Entry<Object, List<Object>> columnEntry : rowEntry.getValue().entrySet()) {
                Object aggregatedValue = aggregateValues(columnEntry.getValue(), aggregation);
                columnResults.put(columnEntry.getKey(), aggregatedValue);
            }
            
            pivotResult.put(rowEntry.getKey(), columnResults);
        }
        
        return pivotResult;
    }
    
    /**
     * Extracts group key from a result based on group-by fields.
     */
    private Map<String, Object> extractGroupKey(CrossSchemaResult result, 
                                               Map<PlayerDataSchema<?>, List<String>> groupByFields) {
        Map<String, Object> key = new LinkedHashMap<>();
        
        for (Map.Entry<PlayerDataSchema<?>, List<String>> entry : groupByFields.entrySet()) {
            PlayerDataSchema<?> schema = entry.getKey();
            for (String fieldName : entry.getValue()) {
                Object value = result.getField(schema, fieldName);
                key.put(schema.schemaKey() + "." + fieldName, value);
            }
        }
        
        return key;
    }
    
    /**
     * Applies an aggregation function to a group of results.
     */
    private Object applyAggregation(List<CrossSchemaResult> results, AggregationSpec spec) {
        List<Object> values = results.stream()
            .map(r -> r.getField(spec.getSchema(), spec.getFieldName()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        
        return aggregateValues(values, spec.getFunction());
    }
    
    /**
     * Aggregates a list of values using the specified function.
     */
    private Object aggregateValues(List<Object> values, AggregationFunction function) {
        if (values.isEmpty()) {
            return null;
        }
        
        switch (function) {
            case COUNT:
                return values.size();
                
            case SUM:
                return values.stream()
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .mapToDouble(Number::doubleValue)
                    .sum();
                
            case AVG:
                return values.stream()
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .mapToDouble(Number::doubleValue)
                    .average()
                    .orElse(0.0);
                
            case MIN:
                return values.stream()
                    .filter(Comparable.class::isInstance)
                    .map(Comparable.class::cast)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
                
            case MAX:
                return values.stream()
                    .filter(Comparable.class::isInstance)
                    .map(Comparable.class::cast)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
                
            case FIRST:
                return values.get(0);
                
            case LAST:
                return values.get(values.size() - 1);
                
            case COLLECT_LIST:
                return new ArrayList<>(values);
                
            case COLLECT_SET:
                return new HashSet<>(values);
                
            default:
                throw new UnsupportedOperationException("Unsupported aggregation function: " + function);
        }
    }
    
    /**
     * Helper method to add schema data without type checking.
     * This is needed because we're working with generic types.
     */
    @SuppressWarnings("unchecked")
    private void addSchemaDataUnchecked(CrossSchemaResult result, PlayerDataSchema schema, Object data) {
        result.addSchemaData(schema, data);
    }
}