package sh.harold.fulcrum.api.data.query.backend;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.query.*;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Query optimizer that analyzes and optimizes cross-schema queries before execution.
 * 
 * <p>This optimizer applies various optimization strategies:</p>
 * <ul>
 *   <li>Join reordering based on estimated cardinality</li>
 *   <li>Filter pushdown to minimize data loading</li>
 *   <li>Redundant operation elimination</li>
 *   <li>Query plan cost estimation</li>
 *   <li>Index usage recommendations</li>
 *   <li>Caching strategy suggestions</li>
 * </ul>
 * 
 * <p>The optimizer works with all backend types and provides both automatic
 * optimizations and recommendations for manual optimization.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class QueryOptimizer {
    
    private static final Logger LOGGER = Logger.getLogger(QueryOptimizer.class.getName());
    
    /**
     * Statistics cache for cardinality estimation.
     */
    private final Map<PlayerDataSchema<?>, SchemaStatistics> statisticsCache = new ConcurrentHashMap<>();
    
    /**
     * Query plan cache for repeated queries.
     */
    private final Map<String, OptimizedQueryPlan> queryPlanCache = new ConcurrentHashMap<>();
    
    /**
     * Configuration for the optimizer.
     */
    private final OptimizerConfiguration configuration;
    
    /**
     * Default singleton instance.
     */
    private static final QueryOptimizer DEFAULT_INSTANCE = new QueryOptimizer();
    
    /**
     * Configuration class for the optimizer.
     */
    public static class OptimizerConfiguration {
        private boolean enableJoinReordering = true;
        private boolean enableFilterPushdown = true;
        private boolean enableQueryPlanCaching = true;
        private boolean collectStatistics = true;
        private int maxCachedPlans = 1000;
        private long statisticsTTL = 3600000; // 1 hour
        
        public OptimizerConfiguration enableJoinReordering(boolean enable) {
            this.enableJoinReordering = enable;
            return this;
        }
        
        public OptimizerConfiguration enableFilterPushdown(boolean enable) {
            this.enableFilterPushdown = enable;
            return this;
        }
        
        public OptimizerConfiguration enableQueryPlanCaching(boolean enable) {
            this.enableQueryPlanCaching = enable;
            return this;
        }
        
        public OptimizerConfiguration collectStatistics(boolean collect) {
            this.collectStatistics = collect;
            return this;
        }
        
        public OptimizerConfiguration maxCachedPlans(int max) {
            this.maxCachedPlans = max;
            return this;
        }
        
        public OptimizerConfiguration statisticsTTL(long ttl) {
            this.statisticsTTL = ttl;
            return this;
        }
    }
    
    /**
     * Creates a new QueryOptimizer with default configuration.
     */
    public QueryOptimizer() {
        this(new OptimizerConfiguration());
    }
    
    /**
     * Creates a new QueryOptimizer with specified configuration.
     * 
     * @param configuration The optimizer configuration
     */
    public QueryOptimizer(OptimizerConfiguration configuration) {
        this.configuration = configuration;
    }
    
    /**
     * Gets the default optimizer instance.
     * 
     * @return The default optimizer
     */
    public static QueryOptimizer getDefault() {
        return DEFAULT_INSTANCE;
    }
    
    /**
     * Optimizes a query and returns an optimized query plan.
     * 
     * @param queryBuilder The query to optimize
     * @return An optimized query plan
     */
    public OptimizedQueryPlan optimize(CrossSchemaQueryBuilder queryBuilder) {
        String querySignature = generateQuerySignature(queryBuilder);
        
        // Check cache
        if (configuration.enableQueryPlanCaching) {
            OptimizedQueryPlan cached = queryPlanCache.get(querySignature);
            if (cached != null && !cached.isExpired()) {
                LOGGER.log(Level.FINE, "Using cached query plan for signature: {0}", querySignature);
                return cached;
            }
        }
        
        LOGGER.log(Level.FINE, "Optimizing query for root schema: {0}", queryBuilder.getRootSchema().schemaKey());
        
        // Create initial plan
        OptimizedQueryPlan plan = new OptimizedQueryPlan(queryBuilder);
        
        // Apply optimizations
        if (configuration.enableFilterPushdown) {
            applyFilterPushdown(plan);
        }
        
        if (configuration.enableJoinReordering) {
            optimizeJoinOrder(plan);
        }
        
        // Estimate costs
        estimateQueryCost(plan);
        
        // Generate recommendations
        generateOptimizationRecommendations(plan);
        
        // Cache the plan
        if (configuration.enableQueryPlanCaching) {
            cachePlan(querySignature, plan);
        }
        
        return plan;
    }
    
    /**
     * Applies filter pushdown optimization.
     */
    private void applyFilterPushdown(OptimizedQueryPlan plan) {
        LOGGER.log(Level.FINE, "Applying filter pushdown optimization");
        
        // Group filters by schema
        Map<PlayerDataSchema<?>, List<QueryFilter>> filtersBySchema = new HashMap<>();
        
        for (QueryFilter filter : plan.getAllFilters()) {
            filtersBySchema.computeIfAbsent(filter.getSchema(), k -> new ArrayList<>()).add(filter);
        }
        
        // Mark filters that can be pushed down
        for (Map.Entry<PlayerDataSchema<?>, List<QueryFilter>> entry : filtersBySchema.entrySet()) {
            PlayerDataSchema<?> schema = entry.getKey();
            List<QueryFilter> filters = entry.getValue();
            
            for (QueryFilter filter : filters) {
                if (canPushDownFilter(filter, schema)) {
                    plan.markFilterForPushdown(filter);
                }
            }
        }
        
        plan.addOptimizationNote("Filter pushdown", 
            String.format("Pushed down %d filters to backend level", plan.getPushdownFilters().size()));
    }
    
    /**
     * Checks if a filter can be pushed down to the backend.
     */
    private boolean canPushDownFilter(QueryFilter filter, PlayerDataSchema<?> schema) {
        // Filters with standard operators can usually be pushed down
        if (filter.getOperator() != null) {
            switch (filter.getOperator()) {
                case EQUALS:
                case NOT_EQUALS:
                case GREATER_THAN:
                case GREATER_THAN_OR_EQUAL:
                case LESS_THAN:
                case LESS_THAN_OR_EQUAL:
                case IN:
                case IS_NULL:
                case IS_NOT_NULL:
                    return true;
                case LIKE:
                case STARTS_WITH:
                case ENDS_WITH:
                    // These depend on backend support
                    PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
                    return backend != null && supportsStringOperations(backend);
                default:
                    return false;
            }
        }
        
        // Custom predicates cannot be pushed down
        return false;
    }
    
    /**
     * Checks if a backend supports string operations.
     */
    private boolean supportsStringOperations(PlayerDataBackend backend) {
        // SQL and MongoDB support string operations
        String className = backend.getClass().getSimpleName();
        return className.contains("Sql") || className.contains("Mongo");
    }
    
    /**
     * Optimizes the join order based on estimated cardinality.
     */
    private void optimizeJoinOrder(OptimizedQueryPlan plan) {
        LOGGER.log(Level.FINE, "Optimizing join order");
        
        List<JoinOperation> joins = new ArrayList<>(plan.getJoins());
        if (joins.size() <= 1) {
            return; // No optimization needed
        }
        
        // Get statistics for all schemas
        Map<PlayerDataSchema<?>, SchemaStatistics> stats = new HashMap<>();
        stats.put(plan.getRootSchema(), getStatistics(plan.getRootSchema()));
        
        for (JoinOperation join : joins) {
            stats.put(join.getTargetSchema(), getStatistics(join.getTargetSchema()));
        }
        
        // Sort joins by estimated selectivity (most selective first)
        joins.sort((j1, j2) -> {
            double selectivity1 = estimateJoinSelectivity(j1, stats);
            double selectivity2 = estimateJoinSelectivity(j2, stats);
            return Double.compare(selectivity1, selectivity2);
        });
        
        // Update the plan with optimized join order
        plan.setOptimizedJoinOrder(joins);
        
        plan.addOptimizationNote("Join reordering", 
            "Reordered joins based on estimated selectivity");
    }
    
    /**
     * Estimates the selectivity of a join operation.
     */
    private double estimateJoinSelectivity(JoinOperation join, Map<PlayerDataSchema<?>, SchemaStatistics> stats) {
        SchemaStatistics targetStats = stats.get(join.getTargetSchema());
        if (targetStats == null || targetStats.estimatedCardinality == 0) {
            return 1.0; // Unknown selectivity
        }
        
        // Estimate based on join type and filters
        double baseSelectivity = 1.0;
        
        switch (join.getJoinType()) {
            case INNER:
                baseSelectivity = 0.5; // Assume 50% match rate for inner joins
                break;
            case LEFT:
            case RIGHT:
                baseSelectivity = 0.8; // Outer joins typically have higher selectivity
                break;
            case FULL:
                baseSelectivity = 1.0; // Full joins include everything
                break;
        }
        
        // Adjust based on filters
        for (QueryFilter filter : join.getFilters()) {
            baseSelectivity *= estimateFilterSelectivity(filter);
        }
        
        return baseSelectivity;
    }
    
    /**
     * Estimates the selectivity of a filter.
     */
    private double estimateFilterSelectivity(QueryFilter filter) {
        if (filter.getOperator() == null) {
            return 0.5; // Unknown selectivity for custom predicates
        }
        
        switch (filter.getOperator()) {
            case EQUALS:
                return 0.1; // Equality is typically very selective
            case NOT_EQUALS:
                return 0.9; // Inequality is not selective
            case GREATER_THAN:
            case LESS_THAN:
                return 0.3; // Range queries are moderately selective
            case IN:
                if (filter.getValue() instanceof Collection) {
                    int size = ((Collection<?>) filter.getValue()).size();
                    return Math.min(0.1 * size, 0.5);
                }
                return 0.2;
            case IS_NULL:
                return 0.05; // Nulls are typically rare
            case IS_NOT_NULL:
                return 0.95; // Most values are not null
            case LIKE:
            case STARTS_WITH:
            case ENDS_WITH:
                return 0.25; // Pattern matching is moderately selective
            default:
                return 0.5;
        }
    }
    
    /**
     * Estimates the cost of executing the query.
     */
    private void estimateQueryCost(OptimizedQueryPlan plan) {
        double totalCost = 0.0;
        
        // Cost of loading root schema data
        SchemaStatistics rootStats = getStatistics(plan.getRootSchema());
        double rootCost = rootStats.estimatedCardinality * rootStats.avgRecordSize / 1000.0;
        totalCost += rootCost;
        
        // Cost of joins
        for (JoinOperation join : plan.getOptimizedJoinOrder()) {
            SchemaStatistics joinStats = getStatistics(join.getTargetSchema());
            double joinCost = joinStats.estimatedCardinality * joinStats.avgRecordSize / 1000.0;
            
            // Add join overhead
            joinCost *= 1.2; // 20% overhead for join operations
            
            totalCost += joinCost;
        }
        
        // Cost of sorting
        if (!plan.getSortOrders().isEmpty()) {
            totalCost *= 1.1; // 10% overhead for sorting
        }
        
        plan.setEstimatedCost(totalCost);
        plan.addOptimizationNote("Cost estimation", 
            String.format("Estimated query cost: %.2f units", totalCost));
    }
    
    /**
     * Generates optimization recommendations.
     */
    private void generateOptimizationRecommendations(OptimizedQueryPlan plan) {
        List<String> recommendations = new ArrayList<>();
        
        // Check for missing indexes
        for (QueryFilter filter : plan.getAllFilters()) {
            if (filter.getOperator() == QueryFilter.FilterOperator.EQUALS) {
                recommendations.add(String.format(
                    "Consider adding index on %s.%s for better filter performance",
                    filter.getSchema().schemaKey(), filter.getFieldName()));
            }
        }
        
        // Check for large result sets
        SchemaStatistics rootStats = getStatistics(plan.getRootSchema());
        if (rootStats.estimatedCardinality > 100000 && !plan.getLimit().isPresent()) {
            recommendations.add("Consider adding pagination (LIMIT/OFFSET) for large result sets");
        }
        
        // Check for complex joins
        if (plan.getJoins().size() > 3) {
            recommendations.add("Complex query with many joins - consider denormalizing data or using materialized views");
        }
        
        // Check for full table scans
        if (plan.getAllFilters().isEmpty()) {
            recommendations.add("No filters detected - query will perform full table scan");
        }
        
        plan.setRecommendations(recommendations);
    }
    
    /**
     * Gets or collects statistics for a schema.
     */
    private SchemaStatistics getStatistics(PlayerDataSchema<?> schema) {
        if (!configuration.collectStatistics) {
            return SchemaStatistics.unknown();
        }
        
        return statisticsCache.computeIfAbsent(schema, s -> {
            LOGGER.log(Level.FINE, "Collecting statistics for schema: {0}", s.schemaKey());
            
            // In a real implementation, this would query the backend for statistics
            // For now, we'll use estimates based on schema type
            SchemaStatistics stats = new SchemaStatistics();
            stats.schemaKey = s.schemaKey();
            stats.lastUpdated = System.currentTimeMillis();
            
            // Estimate based on backend type
            PlayerDataBackend backend = PlayerDataRegistry.getBackend(s);
            if (backend != null) {
                String backendType = backend.getClass().getSimpleName();
                if (backendType.contains("Sql")) {
                    stats.estimatedCardinality = 10000;
                    stats.avgRecordSize = 500;
                } else if (backendType.contains("Mongo")) {
                    stats.estimatedCardinality = 50000;
                    stats.avgRecordSize = 1000;
                } else if (backendType.contains("Json")) {
                    stats.estimatedCardinality = 5000;
                    stats.avgRecordSize = 800;
                }
            }
            
            return stats;
        });
    }
    
    /**
     * Generates a signature for a query for caching purposes.
     */
    private String generateQuerySignature(CrossSchemaQueryBuilder queryBuilder) {
        StringBuilder sig = new StringBuilder();
        sig.append(queryBuilder.getRootSchema().schemaKey());
        
        for (JoinOperation join : queryBuilder.getJoins()) {
            sig.append(":").append(join.getTargetSchema().schemaKey());
            sig.append(":").append(join.getJoinType());
        }
        
        for (QueryFilter filter : queryBuilder.getFilters()) {
            sig.append(":F").append(filter.getFieldName());
            if (filter.getOperator() != null) {
                sig.append(filter.getOperator().ordinal());
            }
        }
        
        for (SortOrder sort : queryBuilder.getSortOrders()) {
            sig.append(":S").append(sort.getFieldName());
            sig.append(sort.getDirection().ordinal());
        }
        
        queryBuilder.getLimit().ifPresent(limit -> sig.append(":L").append(limit));
        queryBuilder.getOffset().ifPresent(offset -> sig.append(":O").append(offset));
        
        return sig.toString();
    }
    
    /**
     * Caches a query plan.
     */
    private void cachePlan(String signature, OptimizedQueryPlan plan) {
        if (queryPlanCache.size() > configuration.maxCachedPlans) {
            // Simple eviction - remove oldest
            queryPlanCache.entrySet().stream()
                .min(Map.Entry.comparingByValue(Comparator.comparing(p -> p.createdAt)))
                .ifPresent(entry -> queryPlanCache.remove(entry.getKey()));
        }
        
        queryPlanCache.put(signature, plan);
    }
    
    /**
     * Clears all caches.
     */
    public void clearCaches() {
        statisticsCache.clear();
        queryPlanCache.clear();
        LOGGER.log(Level.INFO, "Cleared optimizer caches");
    }
    
    /**
     * Container for schema statistics.
     */
    private static class SchemaStatistics {
        String schemaKey;
        long estimatedCardinality = 0;
        long avgRecordSize = 0;
        long lastUpdated = 0;
        
        boolean isExpired(long ttl) {
            return System.currentTimeMillis() - lastUpdated > ttl;
        }
        
        static SchemaStatistics unknown() {
            SchemaStatistics stats = new SchemaStatistics();
            stats.estimatedCardinality = 1000;
            stats.avgRecordSize = 500;
            return stats;
        }
    }
    
    /**
     * Optimized query execution plan.
     */
    public static class OptimizedQueryPlan {
        private final CrossSchemaQueryBuilder originalQuery;
        private final long createdAt;
        private final Map<String, Object> optimizationNotes = new LinkedHashMap<>();
        
        private List<JoinOperation> optimizedJoinOrder;
        private Set<QueryFilter> pushdownFilters = new HashSet<>();
        private double estimatedCost = 0.0;
        private List<String> recommendations = new ArrayList<>();
        
        public OptimizedQueryPlan(CrossSchemaQueryBuilder originalQuery) {
            this.originalQuery = originalQuery;
            this.createdAt = System.currentTimeMillis();
            this.optimizedJoinOrder = new ArrayList<>(originalQuery.getJoins());
        }
        
        // Getters and setters
        
        public CrossSchemaQueryBuilder getOriginalQuery() {
            return originalQuery;
        }
        
        public PlayerDataSchema<?> getRootSchema() {
            return originalQuery.getRootSchema();
        }
        
        public List<JoinOperation> getJoins() {
            return originalQuery.getJoins();
        }
        
        public List<JoinOperation> getOptimizedJoinOrder() {
            return Collections.unmodifiableList(optimizedJoinOrder);
        }
        
        public void setOptimizedJoinOrder(List<JoinOperation> order) {
            this.optimizedJoinOrder = new ArrayList<>(order);
        }
        
        public List<QueryFilter> getAllFilters() {
            List<QueryFilter> allFilters = new ArrayList<>(originalQuery.getFilters());
            for (JoinOperation join : originalQuery.getJoins()) {
                allFilters.addAll(join.getFilters());
            }
            return allFilters;
        }
        
        public Set<QueryFilter> getPushdownFilters() {
            return Collections.unmodifiableSet(pushdownFilters);
        }
        
        public void markFilterForPushdown(QueryFilter filter) {
            pushdownFilters.add(filter);
        }
        
        public List<SortOrder> getSortOrders() {
            return originalQuery.getSortOrders();
        }
        
        public Optional<Integer> getLimit() {
            return originalQuery.getLimit();
        }
        
        public Optional<Integer> getOffset() {
            return originalQuery.getOffset();
        }
        
        public double getEstimatedCost() {
            return estimatedCost;
        }
        
        public void setEstimatedCost(double cost) {
            this.estimatedCost = cost;
        }
        
        public List<String> getRecommendations() {
            return Collections.unmodifiableList(recommendations);
        }
        
        public void setRecommendations(List<String> recommendations) {
            this.recommendations = new ArrayList<>(recommendations);
        }
        
        public void addOptimizationNote(String key, Object value) {
            optimizationNotes.put(key, value);
        }
        
        public Map<String, Object> getOptimizationNotes() {
            return Collections.unmodifiableMap(optimizationNotes);
        }
        
        public boolean isExpired() {
            // Plans expire after 5 minutes
            return System.currentTimeMillis() - createdAt > 300000;
        }
        
        @Override
        public String toString() {
            return "OptimizedQueryPlan{" +
                   "rootSchema=" + getRootSchema().schemaKey() +
                   ", joins=" + optimizedJoinOrder.size() +
                   ", pushdownFilters=" + pushdownFilters.size() +
                   ", estimatedCost=" + String.format("%.2f", estimatedCost) +
                   ", recommendations=" + recommendations.size() +
                   '}';
        }
    }
}