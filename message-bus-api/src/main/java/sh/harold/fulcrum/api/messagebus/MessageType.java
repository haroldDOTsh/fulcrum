package sh.harold.fulcrum.api.messagebus;

import java.lang.annotation.*;

/**
 * Annotation to identify and register message types in the message bus system.
 * Each message class should be annotated with a unique type identifier.
 * 
 * Example:
 * <pre>
 * {@code @MessageType("server.registration.request")}
 * public class ServerRegistrationRequest implements BaseMessage {
 *     // ...
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MessageType {
    /**
     * The unique identifier for this message type.
     * Should follow a hierarchical naming convention (e.g., "category.subcategory.action")
     * 
     * @return the message type identifier
     */
    String value();
    
    /**
     * Optional version number for this message format.
     * Can be used for message evolution and backwards compatibility.
     * 
     * @return the message version (default: 1)
     */
    int version() default 1;
    
    /**
     * Optional channel hint for routing this message type.
     * If not specified, the standard channel routing will be used.
     * 
     * @return the preferred channel for this message type
     */
    String channel() default "";
}