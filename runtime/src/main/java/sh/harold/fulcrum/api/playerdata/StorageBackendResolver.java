package sh.harold.fulcrum.api.playerdata;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;
import sh.harold.fulcrum.api.data.impl.TableSchema;
import sh.harold.fulcrum.api.data.impl.JsonSchema;
import sh.harold.fulcrum.api.data.registry.BackendResolver;

/**
 * Runtime implementation of BackendResolver that delegates to StorageManager.
 * This class bridges the data-api abstraction with the runtime backend initialization.
 */
public class StorageBackendResolver implements BackendResolver {
    
    @Override
    public PlayerDataBackend resolveBackend(PlayerDataSchema<?> schema) {
        if (schema == null) {
            throw new IllegalArgumentException("Schema cannot be null");
        }
        
        // Check if schema is a TableSchema (structured/SQL data)
        if (schema instanceof TableSchema<?>) {
            PlayerDataBackend structuredBackend = getStructuredBackend();
            if (structuredBackend == null) {
                throw new IllegalStateException("Structured backend is not initialized in StorageManager");
            }
            return structuredBackend;
        }
        
        // Check if schema is a JsonSchema (document/JSON data)
        if (schema instanceof JsonSchema<?>) {
            PlayerDataBackend documentBackend = getDocumentBackend();
            if (documentBackend == null) {
                throw new IllegalStateException("Document backend is not initialized in StorageManager");
            }
            return documentBackend;
        }
        
        // Check if schema is AutoTableSchema (special case of TableSchema)
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.AutoTableSchema<?>) {
            PlayerDataBackend structuredBackend = getStructuredBackend();
            if (structuredBackend == null) {
                throw new IllegalStateException("Structured backend is not initialized for AutoTableSchema");
            }
            return structuredBackend;
        }
        
        // Check if schema is GenericJsonSchema (special case of JsonSchema)
        if (schema instanceof sh.harold.fulcrum.api.data.backend.core.GenericJsonSchema) {
            PlayerDataBackend documentBackend = getDocumentBackend();
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
    
    @Override
    public PlayerDataBackend getStructuredBackend() {
        return StorageManager.getStructuredBackend();
    }
    
    @Override
    public PlayerDataBackend getDocumentBackend() {
        return StorageManager.getDocumentBackend();
    }
}