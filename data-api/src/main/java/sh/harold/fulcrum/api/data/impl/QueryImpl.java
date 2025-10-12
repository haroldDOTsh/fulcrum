package sh.harold.fulcrum.api.data.impl;

import sh.harold.fulcrum.api.data.Document;
import sh.harold.fulcrum.api.data.query.Query;
import sh.harold.fulcrum.api.data.storage.StorageBackend;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Default implementation of the Query interface.
 * Builds and executes queries against the storage backend.
 */
public class QueryImpl implements Query {

    private final String collection;
    private final StorageBackend backend;
    private final List<Condition> conditions;
    private String currentPath;
    private LogicalOperator nextOperator = LogicalOperator.AND;
    private Integer limitValue;
    private Integer skipValue;
    private String sortField;
    private boolean sortAscending = true;
    private boolean notModifier = false;
    private String distinctField;
    private String groupByField;
    private Map<String, Object> havingConditions;
    private String[] currentPaths;

    public QueryImpl(String collection, StorageBackend backend) {
        this.collection = collection;
        this.backend = backend;
        this.conditions = new ArrayList<>();
    }

    public Query where(String path) {
        this.currentPath = path;
        return this;
    }

    @Override
    public Query equalTo(Object value) {
        addCondition(notModifier ? ConditionType.NOT_EQUAL : ConditionType.EQUAL, value);
        notModifier = false;
        return this;
    }

    @Override
    public Query notEquals(Object value) {
        addCondition(notModifier ? ConditionType.EQUAL : ConditionType.NOT_EQUAL, value);
        notModifier = false;
        return this;
    }

    @Override
    public Query contains(Object value) {
        addCondition(notModifier ? ConditionType.NOT_CONTAINS : ConditionType.CONTAINS, value);
        notModifier = false;
        return this;
    }

    @Override
    public Query greaterThan(Object value) {
        addCondition(notModifier ? ConditionType.LESS_THAN_OR_EQUAL : ConditionType.GREATER_THAN, value);
        notModifier = false;
        return this;
    }

    @Override
    public Query lessThan(Object value) {
        addCondition(notModifier ? ConditionType.GREATER_THAN_OR_EQUAL : ConditionType.LESS_THAN, value);
        notModifier = false;
        return this;
    }

    @Override
    public Query in(List<?> values) {
        addCondition(notModifier ? ConditionType.NOT_IN : ConditionType.IN, values);
        notModifier = false;
        return this;
    }

    @Override
    public Query matches(String regex) {
        addCondition(notModifier ? ConditionType.NOT_MATCHES : ConditionType.MATCHES, regex);
        notModifier = false;
        return this;
    }

    @Override
    public Query startsWith(String prefix) {
        addCondition(notModifier ? ConditionType.NOT_STARTS_WITH : ConditionType.STARTS_WITH, prefix);
        notModifier = false;
        return this;
    }

    @Override
    public Query endsWith(String suffix) {
        addCondition(notModifier ? ConditionType.NOT_ENDS_WITH : ConditionType.ENDS_WITH, suffix);
        notModifier = false;
        return this;
    }

    @Override
    public Query size(int size) {
        addCondition(notModifier ? ConditionType.NOT_SIZE : ConditionType.SIZE, size);
        notModifier = false;
        return this;
    }

    @Override
    public Query type(Class<?> type) {
        addCondition(notModifier ? ConditionType.NOT_TYPE : ConditionType.TYPE, type);
        notModifier = false;
        return this;
    }

    @Override
    public Query not() {
        this.notModifier = true;
        return this;
    }

    @Override
    public Query isEmpty() {
        addCondition(notModifier ? ConditionType.IS_NOT_EMPTY : ConditionType.IS_EMPTY, null);
        notModifier = false;
        return this;
    }

    @Override
    public Query isNotEmpty() {
        addCondition(notModifier ? ConditionType.IS_EMPTY : ConditionType.IS_NOT_EMPTY, null);
        notModifier = false;
        return this;
    }

    @Override
    public Query and(String path) {
        this.nextOperator = LogicalOperator.AND;
        this.currentPath = path;
        return this;
    }

    @Override
    public Query or(String path) {
        this.nextOperator = LogicalOperator.OR;
        this.currentPath = path;
        this.currentPaths = null;
        return this;
    }

    @Override
    public Query orWhere(Consumer<Query> subQuery) {
        QueryImpl nestedQuery = new QueryImpl(collection, backend);
        subQuery.accept(nestedQuery);
        conditions.add(new Condition(null, ConditionType.NESTED, nestedQuery.conditions, LogicalOperator.OR));
        return this;
    }

    @Override
    public Query andWhere(Consumer<Query> subQuery) {
        QueryImpl nestedQuery = new QueryImpl(collection, backend);
        subQuery.accept(nestedQuery);
        conditions.add(new Condition(null, ConditionType.NESTED, nestedQuery.conditions, LogicalOperator.AND));
        return this;
    }

    @Override
    public Query whereAny(String... paths) {
        this.currentPaths = paths;
        this.currentPath = null;
        return this;
    }

    @Override
    public Query distinct(String path) {
        this.distinctField = path;
        return this;
    }

    @Override
    public Query groupBy(String path) {
        this.groupByField = path;
        return this;
    }

    @Override
    public Query having(String path, Object value) {
        if (havingConditions == null) {
            havingConditions = new HashMap<>();
        }
        havingConditions.put(path, value);
        return this;
    }

    @Override
    public Query limit(int limit) {
        this.limitValue = limit;
        return this;
    }

    @Override
    public Query skip(int skip) {
        this.skipValue = skip;
        return this;
    }

    @Override
    public Query sort(String field, boolean ascending) {
        this.sortField = field;
        this.sortAscending = ascending;
        return this;
    }

    @Override
    public List<Document> execute() {
        try {
            CompletableFuture<List<Document>> future = backend.query(collection, this);
            List<Document> results = future.get();

            // Apply client-side filtering if backend doesn't support it fully
            results = filterDocuments(results);

            // Apply distinct if specified
            if (distinctField != null) {
                results = applyDistinct(results);
            }

            // Apply groupBy if specified
            if (groupByField != null) {
                results = applyGroupBy(results);
            }

            // Apply sorting
            if (sortField != null) {
                results = sortDocuments(results);
            }

            // Apply skip and limit
            results = applyPagination(results);

            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute query", e);
        }
    }

    private List<Document> applyDistinct(List<Document> documents) {
        Map<Object, Document> distinctMap = new LinkedHashMap<>();

        for (Document doc : documents) {
            Object value = doc.get(distinctField);
            // Use the value as key - only keep first document with each distinct value
            if (!distinctMap.containsKey(value)) {
                distinctMap.put(value, doc);
            }
        }

        return new ArrayList<>(distinctMap.values());
    }

    private List<Document> applyGroupBy(List<Document> documents) {
        Map<Object, List<Document>> groups = new LinkedHashMap<>();

        for (Document doc : documents) {
            Object groupValue = doc.get(groupByField);
            // Handle null values in grouping
            if (groupValue == null) {
                continue; // Skip documents with null group values
            }
            groups.computeIfAbsent(groupValue, k -> new ArrayList<>()).add(doc);
        }

        List<Document> results = new ArrayList<>();

        for (Map.Entry<Object, List<Document>> entry : groups.entrySet()) {
            // Apply having conditions if specified
            if (havingConditions != null) {
                boolean matchesHaving = true;
                for (Map.Entry<String, Object> havingEntry : havingConditions.entrySet()) {
                    boolean matches = false;
                    for (Document doc : entry.getValue()) {
                        if (Objects.equals(doc.get(havingEntry.getKey()), havingEntry.getValue())) {
                            matches = true;
                            break;
                        }
                    }
                    if (!matches) {
                        matchesHaving = false;
                        break;
                    }
                }
                if (!matchesHaving) {
                    continue;
                }
            }

            // Add first document from each group as representative
            results.add(entry.getValue().get(0));
        }

        return results;
    }

    @Override
    public long count() {
        try {
            // Get all documents from backend first
            CompletableFuture<List<Document>> future = backend.query(collection, this);
            List<Document> results = future.get();

            // Apply client-side filtering
            results = filterDocuments(results);

            // Don't apply distinct, groupBy, sorting, or pagination for counting
            return results.size();
        } catch (Exception e) {
            throw new RuntimeException("Failed to count documents", e);
        }
    }

    @Override
    public Document first() {
        List<Document> results = limit(1).execute();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public double sum(String path) {
        List<Document> documents = execute();
        double sum = 0;
        for (Document doc : documents) {
            Object value = doc.get(path);
            if (value instanceof Number) {
                sum += ((Number) value).doubleValue();
            }
        }
        return sum;
    }

    @Override
    public double avg(String path) {
        List<Document> documents = execute();
        if (documents.isEmpty()) {
            return 0;
        }

        double sum = 0;
        int count = 0;
        for (Document doc : documents) {
            Object value = doc.get(path);
            if (value instanceof Number) {
                sum += ((Number) value).doubleValue();
                count++;
            }
        }

        return count > 0 ? sum / count : 0;
    }

    @Override
    public Object min(String path) {
        List<Document> documents = execute();
        Object min = null;

        for (Document doc : documents) {
            Object value = doc.get(path);
            if (value != null) {
                if (min == null) {
                    min = value;
                } else if (value instanceof Comparable && min instanceof Comparable) {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> compValue = (Comparable<Object>) value;
                    if (compValue.compareTo(min) < 0) {
                        min = value;
                    }
                }
            }
        }

        return min;
    }

    @Override
    public Object max(String path) {
        List<Document> documents = execute();
        Object max = null;

        for (Document doc : documents) {
            Object value = doc.get(path);
            if (value != null) {
                if (max == null) {
                    max = value;
                } else if (value instanceof Comparable && max instanceof Comparable) {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> compValue = (Comparable<Object>) value;
                    if (compValue.compareTo(max) > 0) {
                        max = value;
                    }
                }
            }
        }

        return max;
    }

    @Override
    public Object aggregate(String path, AggregateFunction function) {
        List<Document> documents = execute();
        List<Object> values = new ArrayList<>();

        for (Document doc : documents) {
            Object value = doc.get(path);
            if (value != null) {
                values.add(value);
            }
        }

        return function.apply(values);
    }

    private void addCondition(ConditionType type, Object value) {
        if (currentPath == null && currentPaths == null) {
            throw new IllegalStateException("No path specified. Call where() or and()/or() first.");
        }

        if (currentPaths != null) {
            // Create OR conditions for whereAny
            List<Condition> anyConditions = new ArrayList<>();
            for (String path : currentPaths) {
                anyConditions.add(new Condition(path, type, value, LogicalOperator.OR));
            }
            conditions.add(new Condition(null, ConditionType.ANY, anyConditions, nextOperator));
            currentPaths = null;
        } else {
            conditions.add(new Condition(currentPath, type, value, nextOperator));
        }
        nextOperator = LogicalOperator.AND;
    }

    private List<Document> filterDocuments(List<Document> documents) {
        if (conditions.isEmpty()) {
            return documents;
        }

        return documents.stream()
                .filter(this::matchesConditions)
                .collect(Collectors.toList());
    }

    private boolean matchesConditions(Document document) {
        if (conditions.isEmpty()) {
            return true;
        }

        // Group conditions by operator for proper evaluation
        boolean result = evaluateCondition(conditions.get(0), document);

        for (int i = 1; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            boolean conditionResult = evaluateCondition(condition, document);

            if (condition.operator == LogicalOperator.OR) {
                // For OR, if either side is true, the whole expression is true
                result = result || conditionResult;
            } else {
                // For AND, both sides must be true
                result = result && conditionResult;
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateCondition(Condition condition, Document document) {
        // Handle nested conditions
        if (condition.type == ConditionType.NESTED) {
            List<Condition> nestedConditions = (List<Condition>) condition.value;
            return matchesConditionList(document, nestedConditions);
        }

        // Handle ANY conditions (whereAny)
        if (condition.type == ConditionType.ANY) {
            List<Condition> anyConditions = (List<Condition>) condition.value;
            for (Condition c : anyConditions) {
                if (evaluateCondition(c, document)) {
                    return true;
                }
            }
            return false;
        }

        Object value = document.get(condition.path);

        switch (condition.type) {
            case EQUAL:
                return Objects.equals(value, condition.value);

            case NOT_EQUAL:
                // NOT modifier logic - check inequality
                return !Objects.equals(value, condition.value);

            case CONTAINS:
                if (value instanceof List) {
                    return ((List<?>) value).contains(condition.value);
                }
                if (value instanceof String && condition.value instanceof String) {
                    return ((String) value).contains((String) condition.value);
                }
                return false;

            case NOT_CONTAINS:
                if (value instanceof List) {
                    return !((List<?>) value).contains(condition.value);
                }
                if (value instanceof String && condition.value instanceof String) {
                    return !((String) value).contains((String) condition.value);
                }
                return true;

            case GREATER_THAN:
                if (value instanceof Comparable && condition.value instanceof Comparable) {
                    return ((Comparable) value).compareTo(condition.value) > 0;
                }
                return false;

            case LESS_THAN:
                if (value instanceof Comparable && condition.value instanceof Comparable) {
                    return ((Comparable) value).compareTo(condition.value) < 0;
                }
                return false;

            case GREATER_THAN_OR_EQUAL:
                if (value instanceof Comparable && condition.value instanceof Comparable) {
                    return ((Comparable) value).compareTo(condition.value) >= 0;
                }
                return false;

            case LESS_THAN_OR_EQUAL:
                if (value instanceof Comparable && condition.value instanceof Comparable) {
                    return ((Comparable) value).compareTo(condition.value) <= 0;
                }
                return false;

            case IN:
                if (condition.value instanceof List) {
                    return ((List<?>) condition.value).contains(value);
                }
                return false;

            case NOT_IN:
                if (condition.value instanceof List) {
                    return !((List<?>) condition.value).contains(value);
                }
                return true;

            case MATCHES:
                if (value instanceof String && condition.value instanceof String) {
                    Pattern pattern = Pattern.compile((String) condition.value);
                    return pattern.matcher((String) value).matches();
                }
                return false;

            case NOT_MATCHES:
                if (value instanceof String && condition.value instanceof String) {
                    Pattern pattern = Pattern.compile((String) condition.value);
                    return !pattern.matcher((String) value).matches();
                }
                return true;

            case STARTS_WITH:
                if (value instanceof String && condition.value instanceof String) {
                    return ((String) value).startsWith((String) condition.value);
                }
                return false;

            case NOT_STARTS_WITH:
                if (value instanceof String && condition.value instanceof String) {
                    return !((String) value).startsWith((String) condition.value);
                }
                return true;

            case ENDS_WITH:
                if (value instanceof String && condition.value instanceof String) {
                    return ((String) value).endsWith((String) condition.value);
                }
                return false;

            case NOT_ENDS_WITH:
                if (value instanceof String && condition.value instanceof String) {
                    return !((String) value).endsWith((String) condition.value);
                }
                return true;

            case SIZE:
                int expectedSize = (Integer) condition.value;
                if (value instanceof String) {
                    return ((String) value).length() == expectedSize;
                }
                if (value instanceof Collection) {
                    return ((Collection<?>) value).size() == expectedSize;
                }
                if (value instanceof Map) {
                    return ((Map<?, ?>) value).size() == expectedSize;
                }
                return false;

            case NOT_SIZE:
                int notExpectedSize = (Integer) condition.value;
                if (value instanceof String) {
                    return ((String) value).length() != notExpectedSize;
                }
                if (value instanceof Collection) {
                    return ((Collection<?>) value).size() != notExpectedSize;
                }
                if (value instanceof Map) {
                    return ((Map<?, ?>) value).size() != notExpectedSize;
                }
                return true;

            case TYPE:
                Class<?> expectedType = (Class<?>) condition.value;
                // Special handling for numeric types stored as different number types
                if (value != null) {
                    if (expectedType == Integer.class) {
                        // Accept any Number type for Integer checks
                        return value instanceof Number;
                    }
                    return expectedType.isInstance(value);
                }
                return false;

            case NOT_TYPE:
                Class<?> notExpectedType = (Class<?>) condition.value;
                // Special handling for numeric types
                if (value != null) {
                    if (notExpectedType == Integer.class) {
                        // For Integer NOT checks, reject any Number type
                        return !(value instanceof Number);
                    }
                    return !notExpectedType.isInstance(value);
                }
                // null is considered NOT of any type
                return true;

            case IS_EMPTY:
                if (value == null) return false;  // null is not considered "empty"
                if (value instanceof String) return ((String) value).isEmpty();
                if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
                if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
                return false;

            case IS_NOT_EMPTY:
                if (value == null) return false;
                if (value instanceof String) return !((String) value).isEmpty();
                if (value instanceof Collection) return !((Collection<?>) value).isEmpty();
                if (value instanceof Map) return !((Map<?, ?>) value).isEmpty();
                return true;

            default:
                return false;
        }
    }

    private boolean matchesConditionList(Document document, List<Condition> conditionList) {
        if (conditionList.isEmpty()) {
            return true;
        }

        boolean result = evaluateCondition(conditionList.get(0), document);

        for (int i = 1; i < conditionList.size(); i++) {
            Condition condition = conditionList.get(i);
            boolean conditionResult = evaluateCondition(condition, document);

            if (condition.operator == LogicalOperator.AND) {
                result = result && conditionResult;
            } else {
                result = result || conditionResult;
            }
        }

        return result;
    }

    private List<Document> sortDocuments(List<Document> documents) {
        List<Document> sorted = new ArrayList<>(documents);

        sorted.sort((doc1, doc2) -> {
            Object val1 = doc1.get(sortField);
            Object val2 = doc2.get(sortField);

            if (val1 == null && val2 == null) return 0;
            if (val1 == null) return sortAscending ? -1 : 1;
            if (val2 == null) return sortAscending ? 1 : -1;

            if (val1 instanceof Comparable && val2 instanceof Comparable) {
                @SuppressWarnings("unchecked")
                int comp = ((Comparable) val1).compareTo(val2);
                return sortAscending ? comp : -comp;
            }

            return 0;
        });

        return sorted;
    }

    private List<Document> applyPagination(List<Document> documents) {
        int start = skipValue != null ? skipValue : 0;
        int end = documents.size();

        if (limitValue != null) {
            end = Math.min(start + limitValue, end);
        }

        if (start >= documents.size()) {
            return new ArrayList<>();
        }

        return documents.subList(start, end);
    }

    // Public for backend access
    public List<Condition> getConditions() {
        return conditions;
    }

    public enum ConditionType {
        EQUAL, NOT_EQUAL, CONTAINS, NOT_CONTAINS, GREATER_THAN, LESS_THAN,
        GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL, IN, NOT_IN,
        MATCHES, NOT_MATCHES, STARTS_WITH, NOT_STARTS_WITH,
        ENDS_WITH, NOT_ENDS_WITH, SIZE, NOT_SIZE,
        TYPE, NOT_TYPE, IS_EMPTY, IS_NOT_EMPTY,
        NESTED, ANY
    }

    public enum LogicalOperator {
        AND, OR
    }

    public record Condition(String path, ConditionType type, Object value, LogicalOperator operator) {
    }
}