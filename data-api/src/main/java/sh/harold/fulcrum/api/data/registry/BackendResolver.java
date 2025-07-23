package sh.harold.fulcrum.api.data.registry;

import sh.harold.fulcrum.api.data.backend.PlayerDataBackend;
import sh.harold.fulcrum.api.data.impl.PlayerDataSchema;

/**
 * Interface for resolving appropriate backends for different schema types.
 * This abstraction allows the data-api module to request backend resolution
 * without directly depending on the runtime module's implementation.
 */
public interface BackendResolver {
    
    /**
     * Resolves the appropriate backend for a given schema.
     * This method automatically determines the backend based on the schema type.
     * 
     * @param schema The schema to resolve backend for
     * @return The appropriate PlayerDataBackend
     * @throws IllegalArgumentException if schema type is not recognized
     * @throws IllegalStateException if required backend is not initialized
     */
    PlayerDataBackend resolveBackend(PlayerDataSchema<?> schema);
    
    /**
     * Gets the structured/SQL backend for TableSchema-based data.
     * 
     * @return The structured backend
     * @throws IllegalStateException if structured backend is not initialized
     */
    PlayerDataBackend getStructuredBackend();
    
    /**
     * Gets the document/JSON backend for JsonSchema-based data.
     * 
     * @return The document backend  
     * @throws IllegalStateException if document backend is not initialized
     */
    PlayerDataBackend getDocumentBackend();
}