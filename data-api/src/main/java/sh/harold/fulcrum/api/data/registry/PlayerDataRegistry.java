package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.LifecycleAwareSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.integration.QueryBuilderFactory;
import sh.harold.fulcrum.api.data.integration.PlayerDataQueryRegistry;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class PlayerDataRegistry {
    private static final Map<PlayerDataSchema<?>, PlayerDataBackend> schemaBackends = new HashMap<>();
    private static final Logger logger = Logger.getLogger(PlayerDataRegistry.class.getName());
    private static BackendResolver backendResolver;
    
    private PlayerDataRegistry() {
    }

    /**
     * Registers a BackendResolver implementation with this registry.
     * This method should be called by the runtime module during initialization.
     *
     * @param resolver The BackendResolver implementation
     */
    public static void setBackendResolver(BackendResolver resolver) {
        backendResolver = resolver;
        logger.info("BackendResolver registered: " + (resolver != null ? resolver.getClass().getSimpleName() : "null"));
    }

    /**
     * NEW METHOD: Registers a schema with automatic backend resolution.
     * This method automatically detects the schema type and selects the appropriate backend.
     * 
     * @param <T> The type of data the schema handles
     * @param schema The PlayerDataSchema to register
     * @throws IllegalArgumentException if schema is null or of unknown type
     * @throws IllegalStateException if BackendResolver is not available
     * @since 1.2.0
     */
    public static <T> void registerSchema(PlayerDataSchema<T> schema) {
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        
        // Get BackendResolver from static registry
        if (backendResolver == null) {
            throw new IllegalStateException("BackendResolver is not available. Ensure it is properly registered with PlayerDataRegistry.setBackendResolver().");
        }
        
        // Automatic backend resolution based on schema type
        PlayerDataBackend backend = backendResolver.resolveBackend(schema);
        
        // Log the automatic resolution for debugging
        logger.info(String.format("Auto-registering schema '%s' with backend '%s'", 
            schema.schemaKey(), 
            backend.getClass().getSimpleName()));
        
        // Delegate to existing two-parameter method
        registerSchema(schema, backend);
    }
    

    /**
     * EXISTING METHOD: Registers a schema with an explicitly provided backend.
     * This method is maintained for backward compatibility and advanced use cases.
     * 
     * @deprecated Since 1.2.0, use {@link #registerSchema(PlayerDataSchema)} for automatic backend resolution
     * @param <T> The type of data the schema handles
     * @param schema The PlayerDataSchema to register
     * @param backend The PlayerDataBackend to use for this schema
     */
    @Deprecated(since = "1.2.0", forRemoval = false)
    public static <T> void registerSchema(PlayerDataSchema<T> schema, PlayerDataBackend backend) {
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        if (backend == null) {
            throw new IllegalArgumentException("Backend cannot be null");
        }
        
        schemaBackends.put(schema, backend);
        
        // Automatic SQL table creation and schema version enforcement
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.AutoTableSchema<?> autoSchema &&
                backend instanceof sh.harold.fulcrum.api.data.backend.sql.SqlDataBackend sqlBackend) {
            try {
                autoSchema.ensureTableAndVersion(sqlBackend.getConnection());
            } catch (Exception e) {
                throw new RuntimeException("Failed to ensure table and schema version for " + schema.schemaKey(), e);
            }
        }
    }
    
    /**
     * NEW CONVENIENCE METHOD: Registers a schema with automatic backend resolution and queryable metadata.
     * This combines automatic backend resolution with query registry integration.
     * 
     * @param <T> The type of data the schema handles
     * @param schema The PlayerDataSchema to register
     * @param metadata The schema metadata for query capabilities
     * @since 1.2.0
     */
    public static <T> void registerQueryableSchema(
            PlayerDataSchema<T> schema,
            PlayerDataQueryRegistry.SchemaMetadata metadata) {
        
        // Use new single-parameter registration
        registerSchema(schema);
        
        // Get the resolved backend
        PlayerDataBackend backend = getBackend(schema);
        
        // Register in PlayerDataQueryRegistry
        PlayerDataQueryRegistry.registerQueryableSchema(schema, backend.getClass(), metadata);
    }

    /**
     * EXISTING METHOD: Registers a schema as queryable with metadata.
     * 
     * @deprecated Since 2.1, use {@link #registerQueryableSchema(PlayerDataSchema, PlayerDataQueryRegistry.SchemaMetadata)}
     * @param schema The schema to register
     * @param backend The backend for this schema
     * @param metadata The schema metadata
     * @param <T> The schema data type
     */
    @Deprecated(since = "2.1", forRemoval = false)
    public static <T> void registerQueryableSchema(
            PlayerDataSchema<T> schema,
            PlayerDataBackend backend,
            PlayerDataQueryRegistry.SchemaMetadata metadata) {
        
        // Register in PlayerDataRegistry
        registerSchema(schema, backend);
        
        // Register in PlayerDataQueryRegistry
        PlayerDataQueryRegistry.registerQueryableSchema(schema, backend.getClass(), metadata);
    }

    // ... rest of the existing methods remain unchanged ...
    
    public static <T> PlayerDataBackend getBackend(PlayerDataSchema<T> schema) {
        return schemaBackends.get(schema);
    }

    public static Collection<PlayerDataSchema<?>> allSchemas() {
        return Collections.unmodifiableCollection(schemaBackends.keySet());
    }

    public static void clear() {
        schemaBackends.clear();
    }

    public static void notifyJoin(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onJoin(playerId);
            }
        }
    }

    public static void notifyQuit(UUID playerId) {
        for (var schema : allSchemas()) {
            if (schema instanceof LifecycleAwareSchema lifecycle) {
                lifecycle.onQuit(playerId);
            }
        }
    }
    
    // ... remaining methods stay the same ...
}