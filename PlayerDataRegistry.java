package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.LifecycleAwareSchema;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.query.CrossSchemaQueryBuilder;
import sh.harold.fulcrum.api.data.integration.QueryBuilderFactory;
import sh.harold.fulcrum.api.data.integration.PlayerDataQueryRegistry;
import sh.harold.fulcrum.api.module.ServiceLocator;
import sh.harold.fulcrum.api.module.FulcrumPlatform;
import sh.harold.fulcrum.api.playerdata.StorageManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class PlayerDataRegistry {
    private static final Map<PlayerDataSchema<?>, PlayerDataBackend> schemaBackends = new HashMap<>();
    private static final Logger logger = Logger.getLogger(PlayerDataRegistry.class.getName());
    
    private PlayerDataRegistry() {
    }

    /**
     * NEW METHOD: Registers a schema with automatic backend resolution.
     * This method automatically detects the schema type and selects the appropriate backend.
     * 
     * @param <T> The type of data the schema handles
     * @param schema The PlayerDataSchema to register
     * @throws IllegalArgumentException if schema is null or of unknown type
     * @throws IllegalStateException if ServiceLocator or StorageManager is not available
     * @since 2.1
     */
    public static <T> void registerSchema(PlayerDataSchema<T> schema) {
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        
        // Get StorageManager through ServiceLocator
        ServiceLocator serviceLocator = FulcrumPlatform.getServiceLocator();
        if (serviceLocator == null) {
            throw new IllegalStateException("ServiceLocator is not available. Ensure the platform is properly initialized.");
        }
        
        StorageManager storageManager = serviceLocator.getService(StorageManager.class);
        if (storageManager == null) {
            throw new IllegalStateException("StorageManager is not available. Ensure it is properly registered with ServiceLocator.");
        }
        
        // Automatic backend resolution based on schema type
        PlayerDataBackend backend = resolveBackend(schema, storageManager);
        
        // Log the automatic resolution for debugging
        logger.info(String.format("Auto-registering schema '%s' with backend '%s'", 
            schema.schemaKey(), 
            backend.getClass().getSimpleName()));
        
        // Delegate to existing two-parameter method
        registerSchema(schema, backend);
    }
    
    /**
     * Resolves the appropriate backend for a given schema based on its type.
     * 
     * @param schema The schema to resolve backend for
     * @param storageManager The storage manager instance
     * @return The appropriate PlayerDataBackend
     * @throws IllegalArgumentException if schema type is not recognized
     */
    private static PlayerDataBackend resolveBackend(PlayerDataSchema<?> schema, StorageManager storageManager) {
        // Check if schema is a TableSchema (structured/SQL data)
        if (schema instanceof TableSchema<?>) {
            PlayerDataBackend structuredBackend = storageManager.getStructuredBackend();
            if (structuredBackend == null) {
                throw new IllegalStateException("Structured backend is not initialized in StorageManager");
            }
            return structuredBackend;
        }
        
        // Check if schema is a JsonSchema (document/JSON data)
        if (schema instanceof JsonSchema<?>) {
            PlayerDataBackend documentBackend = storageManager.getDocumentBackend();
            if (documentBackend == null) {
                throw new IllegalStateException("Document backend is not initialized in StorageManager");
            }
            return documentBackend;
        }
        
        // Check if schema is AutoTableSchema (special case of TableSchema)
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.AutoTableSchema<?>) {
            PlayerDataBackend structuredBackend = storageManager.getStructuredBackend();
            if (structuredBackend == null) {
                throw new IllegalStateException("Structured backend is not initialized for AutoTableSchema");
            }
            return structuredBackend;
        }
        
        // Check if schema is GenericJsonSchema (special case of JsonSchema)
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.GenericJsonSchema<?>) {
            PlayerDataBackend documentBackend = storageManager.getDocumentBackend();
            if (documentBackend == null) {
                throw new IllegalStateException("Document backend is not initialized for GenericJsonSchema");
            }
            return documentBackend;
        }
        
        // If we reach here, the schema type is unknown
        throw new IllegalArgumentException(
            String.format("Unknown schema type '%s'. Schema must extend TableSchema or JsonSchema.", 
                schema.getClass().getName())
        );
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
    @Deprecated(since = "2.1", forRemoval = false)
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