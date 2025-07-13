package sh.harold.fulcrum.api.data.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for multiple composite indexes on an entity class.
 * This annotation allows you to define multiple @CompositeIndex annotations
 * on a single class.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * @Table("monthly_ranks_history")
 * @Indexes({
 *     @CompositeIndex(
 *         name = "idx_uuid_expires", 
 *         fields = {"uuid", "expiresAt"}, 
 *         orders = {IndexOrder.ASC, IndexOrder.DESC}
 *     ),
 *     @CompositeIndex(
 *         name = "idx_uuid_granted", 
 *         fields = {"uuid", "grantedAt"}, 
 *         orders = {IndexOrder.ASC, IndexOrder.DESC}
 *     )
 * })
 * public class MonthlyRankHistoryData {
 *     // fields...
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Indexes {
    /**
     * Array of composite index definitions to create for this entity.
     * 
     * @return array of CompositeIndex annotations
     */
    CompositeIndex[] value();
}