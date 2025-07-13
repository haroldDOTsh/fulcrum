package sh.harold.fulcrum.api.data.annotation;

/**
 * Enumeration for specifying the sort order of database indexes.
 * Used in conjunction with @Index and @CompositeIndex annotations.
 */
public enum IndexOrder {
    /**
     * Ascending order (default for most databases)
     */
    ASC,
    
    /**
     * Descending order
     */
    DESC
}