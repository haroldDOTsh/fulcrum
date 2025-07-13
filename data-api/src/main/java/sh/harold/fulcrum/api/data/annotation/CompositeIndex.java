package sh.harold.fulcrum.api.data.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for creating composite (multi-column) database indexes on entity classes.
 * Must be used within an @Indexes annotation container.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * @Indexes({
 *     @CompositeIndex(
 *         name = "idx_player_guild", 
 *         fields = {"playerUuid", "guildId"}
 *     ),
 *     @CompositeIndex(
 *         fields = {"expiresAt", "rank"}, 
 *         orders = {IndexOrder.DESC, IndexOrder.ASC},
 *         unique = true
 *     )
 * })
 * public class GuildMemberData {
 *     public UUID playerUuid;
 *     public UUID guildId;
 *     public long expiresAt;
 *     public String rank;
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CompositeIndex {
    /**
     * Custom name for the index. If empty, an index name will be auto-generated
     * using the pattern: idx_{tableName}_{field1}_{field2}...
     * 
     * @return the index name, or empty string for auto-generation
     */
    String name() default "";
    
    /**
     * The field names to include in the composite index, in order.
     * The order of fields is significant for index performance.
     * 
     * @return array of field names
     */
    String[] fields();
    
    /**
     * The sort order for each field in the index. If not specified or shorter
     * than the fields array, remaining fields default to ASC.
     * 
     * @return array of index orders corresponding to each field
     */
    IndexOrder[] orders() default {};
    
    /**
     * Whether this index should enforce uniqueness constraints across
     * the combination of all specified fields.
     * 
     * @return true if this should be a unique composite index, false otherwise
     */
    boolean unique() default false;
}