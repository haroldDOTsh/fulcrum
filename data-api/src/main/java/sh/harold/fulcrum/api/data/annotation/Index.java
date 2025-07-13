package sh.harold.fulcrum.api.data.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for creating single-column database indexes on entity fields.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public class PlayerData {
 *     @Index(name = "idx_player_uuid")
 *     public UUID uuid;
 *     
 *     @Index(order = IndexOrder.DESC)
 *     public long lastLogin;
 *     
 *     @Index(unique = true)
 *     public String email;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Index {
    /**
     * Custom name for the index. If empty, an index name will be auto-generated
     * using the pattern: idx_{tableName}_{columnName}
     * 
     * @return the index name, or empty string for auto-generation
     */
    String name() default "";
    
    /**
     * Whether this index should enforce uniqueness constraints.
     * 
     * @return true if this should be a unique index, false otherwise
     */
    boolean unique() default false;
    
    /**
     * The sort order for this index.
     * 
     * @return the index order (ASC or DESC)
     */
    IndexOrder order() default IndexOrder.ASC;
}