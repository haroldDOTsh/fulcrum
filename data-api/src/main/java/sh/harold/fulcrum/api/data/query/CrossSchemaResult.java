package sh.harold.fulcrum.api.data.query;

import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

import java.util.*;

/**
 * Represents the result of a cross-schema query, containing data from multiple schemas
 * for a single player UUID.
 * 
 * <p>This class acts as a container for data retrieved from different schemas during
 * a cross-schema join operation. It provides convenient methods to access data by
 * schema type or field name.</p>
 * 
 * @author Harold
 * @since 1.0
 */
public class CrossSchemaResult {
    
    private final UUID playerUuid;
    private final Map<PlayerDataSchema<?>, Object> schemaData;
    private final Map<String, Map<String, Object>> fieldCache;
    
    /**
     * Creates a new CrossSchemaResult for the specified player UUID.
     * 
     * @param playerUuid The UUID of the player this result represents
     */
    public CrossSchemaResult(UUID playerUuid) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "Player UUID cannot be null");
        this.schemaData = new HashMap<>();
        this.fieldCache = new HashMap<>();
    }
    
    /**
     * Creates a new CrossSchemaResult with initial data.
     * 
     * @param playerUuid The UUID of the player this result represents
     * @param schemaData Initial schema data map
     */
    public CrossSchemaResult(UUID playerUuid, Map<PlayerDataSchema<?>, Object> schemaData) {
        this(playerUuid);
        this.schemaData.putAll(schemaData);
        buildFieldCache();
    }
    
    /**
     * Gets the player UUID for this result.
     * 
     * @return The player UUID
     */
    public UUID getPlayerUuid() {
        return playerUuid;
    }
    
    /**
     * Adds data for a specific schema.
     * 
     * @param schema The schema
     * @param data The data object
     * @param <T> The type of the data
     */
    public <T> void addSchemaData(PlayerDataSchema<T> schema, T data) {
        schemaData.put(schema, data);
        invalidateFieldCache(schema);
    }
    
    /**
     * Gets data for a specific schema.
     * 
     * @param schema The schema to get data for
     * @param <T> The expected type of the data
     * @return The data object, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(PlayerDataSchema<T> schema) {
        return (T) schemaData.get(schema);
    }
    
    /**
     * Gets data for a specific schema class.
     * 
     * @param schemaClass The schema class
     * @param <T> The expected type of the data
     * @return The data object, or null if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(Class<? extends PlayerDataSchema<T>> schemaClass) {
        for (Map.Entry<PlayerDataSchema<?>, Object> entry : schemaData.entrySet()) {
            if (schemaClass.isInstance(entry.getKey())) {
                return (T) entry.getValue();
            }
        }
        return null;
    }
    
    /**
     * Checks if data is present for a specific schema.
     * 
     * @param schema The schema to check
     * @return true if data exists for the schema, false otherwise
     */
    public boolean hasData(PlayerDataSchema<?> schema) {
        return schemaData.containsKey(schema);
    }
    
    /**
     * Gets all schemas that have data in this result.
     * 
     * @return Unmodifiable set of schemas
     */
    public Set<PlayerDataSchema<?>> getSchemas() {
        return Collections.unmodifiableSet(schemaData.keySet());
    }
    
    /**
     * Gets a field value by field name, searching across all schemas.
     * If the field exists in multiple schemas, returns the first found.
     * 
     * @param fieldName The field name to search for
     * @return The field value, or null if not found
     */
    public Object getField(String fieldName) {
        // First check cache
        for (Map<String, Object> fields : fieldCache.values()) {
            if (fields.containsKey(fieldName)) {
                return fields.get(fieldName);
            }
        }
        
        // If not in cache, search through data
        for (Map.Entry<PlayerDataSchema<?>, Object> entry : schemaData.entrySet()) {
            Object value = getFieldFromObject(entry.getValue(), fieldName);
            if (value != null) {
                return value;
            }
        }
        
        return null;
    }
    
    /**
     * Gets a field value from a specific schema.
     * 
     * @param schema The schema to get the field from
     * @param fieldName The field name
     * @return The field value, or null if not found
     */
    public Object getField(PlayerDataSchema<?> schema, String fieldName) {
        Object data = schemaData.get(schema);
        if (data == null) {
            return null;
        }
        
        // Check cache first
        Map<String, Object> fields = fieldCache.get(schema.schemaKey());
        if (fields != null && fields.containsKey(fieldName)) {
            return fields.get(fieldName);
        }
        
        return getFieldFromObject(data, fieldName);
    }
    
    /**
     * Gets a typed field value from a specific schema.
     * 
     * @param schema The schema to get the field from
     * @param fieldName The field name
     * @param fieldType The expected field type
     * @param <T> The type of the field
     * @return The field value, or null if not found or wrong type
     */
    @SuppressWarnings("unchecked")
    public <T> T getField(PlayerDataSchema<?> schema, String fieldName, Class<T> fieldType) {
        Object value = getField(schema, fieldName);
        if (value != null && fieldType.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
    
    /**
     * Gets all field names available across all schemas.
     * 
     * @return Set of all field names
     */
    public Set<String> getAllFieldNames() {
        Set<String> fieldNames = new HashSet<>();
        for (Object data : schemaData.values()) {
            fieldNames.addAll(getFieldNamesFromObject(data));
        }
        return fieldNames;
    }
    
    /**
     * Converts this result to a flat map of all fields.
     * Field names are prefixed with schema key if there are conflicts.
     * 
     * @return Map of field names to values
     */
    public Map<String, Object> toFlatMap() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Integer> fieldCounts = new HashMap<>();
        
        // First pass: count field occurrences
        for (Map.Entry<PlayerDataSchema<?>, Object> entry : schemaData.entrySet()) {
            Set<String> fieldNames = getFieldNamesFromObject(entry.getValue());
            for (String fieldName : fieldNames) {
                fieldCounts.merge(fieldName, 1, Integer::sum);
            }
        }
        
        // Second pass: add fields with proper naming
        for (Map.Entry<PlayerDataSchema<?>, Object> entry : schemaData.entrySet()) {
            PlayerDataSchema<?> schema = entry.getKey();
            Object data = entry.getValue();
            Map<String, Object> fields = getFieldsFromObject(data);
            
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                String fieldName = field.getKey();
                // Prefix with schema key if field name appears in multiple schemas
                if (fieldCounts.get(fieldName) > 1) {
                    fieldName = schema.schemaKey() + "." + fieldName;
                }
                result.put(fieldName, field.getValue());
            }
        }
        
        // Always include the UUID
        result.put("uuid", playerUuid);
        
        return result;
    }
    
    /**
     * Builds a cache of field values for faster access.
     */
    private void buildFieldCache() {
        fieldCache.clear();
        for (Map.Entry<PlayerDataSchema<?>, Object> entry : schemaData.entrySet()) {
            String schemaKey = entry.getKey().schemaKey();
            Map<String, Object> fields = getFieldsFromObject(entry.getValue());
            fieldCache.put(schemaKey, fields);
        }
    }
    
    /**
     * Invalidates the field cache for a specific schema.
     */
    private void invalidateFieldCache(PlayerDataSchema<?> schema) {
        fieldCache.remove(schema.schemaKey());
    }
    
    /**
     * Extracts a field value from an object using reflection.
     */
    private Object getFieldFromObject(Object obj, String fieldName) {
        if (obj == null) {
            return null;
        }
        
        try {
            java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            // Field not found or inaccessible
            return null;
        }
    }
    
    /**
     * Gets all field names from an object using reflection.
     */
    private Set<String> getFieldNamesFromObject(Object obj) {
        Set<String> fieldNames = new HashSet<>();
        if (obj == null) {
            return fieldNames;
        }
        
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                // Skip static and synthetic fields
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fieldNames.add(field.getName());
                }
            }
            clazz = clazz.getSuperclass();
        }
        
        return fieldNames;
    }
    
    /**
     * Gets all fields and their values from an object using reflection.
     */
    private Map<String, Object> getFieldsFromObject(Object obj) {
        Map<String, Object> fields = new HashMap<>();
        if (obj == null) {
            return fields;
        }
        
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                // Skip static and synthetic fields
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    try {
                        field.setAccessible(true);
                        fields.put(field.getName(), field.get(obj));
                    } catch (IllegalAccessException e) {
                        // Skip inaccessible fields
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        
        return fields;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CrossSchemaResult that = (CrossSchemaResult) o;
        return Objects.equals(playerUuid, that.playerUuid) &&
               Objects.equals(schemaData, that.schemaData);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(playerUuid, schemaData);
    }
    
    @Override
    public String toString() {
        return "CrossSchemaResult{" +
               "playerUuid=" + playerUuid +
               ", schemas=" + schemaData.keySet().stream()
                   .map(PlayerDataSchema::schemaKey)
                   .collect(java.util.stream.Collectors.joining(", ")) +
               '}';
    }
}