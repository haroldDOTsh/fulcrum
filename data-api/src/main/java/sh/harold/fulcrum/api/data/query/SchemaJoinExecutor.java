package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.registry.PlayerDataRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes cross-schema queries by coordinating data retrieval from multiple backends
 * and applying joins, filters, and sorting.
 */
public class SchemaJoinExecutor {

    private static final Logger LOGGER = Logger.getLogger(SchemaJoinExecutor.class.getName());

    private final ExecutorService executorService;
    private final Map<String, CompletableFuture<Map<UUID, Object>>> schemaDataCache;

    public SchemaJoinExecutor() {
        this(ForkJoinPool.commonPool());
    }

    public SchemaJoinExecutor(ExecutorService executorService) {
        this.executorService = Objects.requireNonNull(executorService, "Executor service cannot be null");
        this.schemaDataCache = new ConcurrentHashMap<>();
    }

    public CompletableFuture<List<CrossSchemaResult>> execute(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = collectSchemas(queryBuilder);
        Map<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> schemaDataFutures = new HashMap<>();
        for (PlayerDataSchema<?> schema : schemas) {
            schemaDataFutures.put(schema, loadSchemaData(schema, queryBuilder));
        }

        return CompletableFuture.allOf(schemaDataFutures.values().toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    try {
                        Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData = new HashMap<>();
                        for (Map.Entry<PlayerDataSchema<?>, CompletableFuture<Map<UUID, Object>>> entry : schemaDataFutures.entrySet()) {
                            schemaData.put(entry.getKey(), entry.getValue().join());
                        }

                        Set<UUID> finalUuids = applyJoins(queryBuilder, schemaData);
                        List<CrossSchemaResult> results = buildResults(finalUuids, schemaData);
                        results = applySorting(results, queryBuilder.getSortOrders());
                        results = applyPagination(results, queryBuilder.getLimit(), queryBuilder.getOffset());

                        return CompletableFuture.completedFuture(results);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error executing cross-schema query", e);
                        return CompletableFuture.failedFuture(e);
                    }
                });
    }

    private Set<PlayerDataSchema<?>> collectSchemas(CrossSchemaQueryBuilder queryBuilder) {
        Set<PlayerDataSchema<?>> schemas = new HashSet<>();
        schemas.add(queryBuilder.getRootSchema());
        for (JoinOperation join : queryBuilder.getJoins()) {
            schemas.add(join.getTargetSchema());
        }
        return schemas;
    }

    private CompletableFuture<Map<UUID, Object>> loadSchemaData(PlayerDataSchema<?> schema, CrossSchemaQueryBuilder queryBuilder) {
        String cacheKey = schema.schemaKey() + ":" + System.identityHashCode(queryBuilder);

        return schemaDataCache.computeIfAbsent(cacheKey, k ->
                CompletableFuture.supplyAsync(() -> {
                    LOGGER.log(Level.FINE, "Loading data for schema: {0}", schema.schemaKey());

                    PlayerDataBackend backend = PlayerDataRegistry.getBackend(schema);
                    if (backend == null) {
                        LOGGER.log(Level.WARNING, "No backend registered for schema: {0}", schema.schemaKey());
                        return Collections.emptyMap();
                    }

                    List<QueryFilter> schemaFilters = getFiltersForSchema(schema, queryBuilder);
                    Optional<Integer> limit = queryBuilder.getLimit();
                    Optional<Integer> offset = queryBuilder.getOffset();

                    if (backend.supportsNativeQueries()) {
                        return backend.query(schema, schemaFilters, limit, offset);
                    } else {
                        Map<UUID, Object> allData = loadAllDataFromBackend(backend, schema);
                        Map<UUID, Object> filteredData = new HashMap<>();
                        for (Map.Entry<UUID, Object> entry : allData.entrySet()) {
                            if (passesFilters(entry.getValue(), schemaFilters)) {
                                filteredData.put(entry.getKey(), entry.getValue());
                            }
                        }
                        return filteredData;
                    }
                }, executorService)
        );
    }

    private Map<UUID, Object> loadAllDataFromBackend(PlayerDataBackend backend, PlayerDataSchema<?> schema) {
        Map<UUID, Object> data = new HashMap<>();
        LOGGER.log(Level.INFO, "Would load all data for schema {0} from backend {1}",
                new Object[]{schema.schemaKey(), backend.getClass().getSimpleName()});
        return data;
    }

    private List<QueryFilter> getFiltersForSchema(PlayerDataSchema<?> schema, CrossSchemaQueryBuilder queryBuilder) {
        List<QueryFilter> filters = new ArrayList<>();
        if (schema.equals(queryBuilder.getRootSchema())) {
            filters.addAll(queryBuilder.getFilters());
        }
        for (JoinOperation join : queryBuilder.getJoins()) {
            if (join.getTargetSchema().equals(schema)) {
                filters.addAll(join.getFilters());
            }
        }
        return filters;
    }

    private boolean passesFilters(Object obj, List<QueryFilter> filters) {
        for (QueryFilter filter : filters) {
            if (!filter.test(obj)) {
                return false;
            }
        }
        return true;
    }

    private Set<UUID> applyJoins(CrossSchemaQueryBuilder queryBuilder, Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData) {
        Set<UUID> result = new HashSet<>(schemaData.get(queryBuilder.getRootSchema()).keySet());
        for (JoinOperation join : queryBuilder.getJoins()) {
            Set<UUID> joinUuids = schemaData.get(join.getTargetSchema()).keySet();
            switch (join.getJoinType()) {
                case INNER:
                    result.retainAll(joinUuids);
                    break;
                case LEFT:
                    break;
                case RIGHT:
                    result = new HashSet<>(joinUuids);
                    break;
                case FULL:
                    result.addAll(joinUuids);
                    break;
            }
        }
        return result;
    }

    private List<CrossSchemaResult> buildResults(Set<UUID> uuids, Map<PlayerDataSchema<?>, Map<UUID, Object>> schemaData) {
        List<CrossSchemaResult> results = new ArrayList<>();
        for (UUID uuid : uuids) {
            CrossSchemaResult result = new CrossSchemaResult(uuid);
            for (Map.Entry<PlayerDataSchema<?>, Map<UUID, Object>> entry : schemaData.entrySet()) {
                PlayerDataSchema<?> schema = entry.getKey();
                Object data = entry.getValue().get(uuid);
                if (data != null) {
                    @SuppressWarnings("unchecked")
                    PlayerDataSchema<Object> objectSchema = (PlayerDataSchema<Object>) schema;
                    result.addSchemaData(objectSchema, data);
                }
            }
            results.add(result);
        }
        return results;
    }

    private List<CrossSchemaResult> applySorting(List<CrossSchemaResult> results, List<SortOrder> sortOrders) {
        if (sortOrders.isEmpty()) {
            return results;
        }
        results.sort((r1, r2) -> {
            for (SortOrder sortOrder : sortOrders) {
                int cmp = sortOrder.compare(r1, r2); // Fix SortOrder.compare() method usage
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        });
        return results;
    }

    private List<CrossSchemaResult> applyPagination(List<CrossSchemaResult> results, Optional<Integer> limit, Optional<Integer> offset) {
        int startIndex = offset.orElse(0);
        int endIndex = limit.map(l -> Math.min(startIndex + l, results.size())).orElse(results.size());
        return results.subList(startIndex, endIndex);
    }
}