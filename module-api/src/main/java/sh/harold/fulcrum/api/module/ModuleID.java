package sh.harold.fulcrum.api.module;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies a module's unique ID for environment configuration.
 * Must be placed on PluginBootstrap implementations.
 * 
 * @since 1.3.1
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleID {
    /**
     * The unique module identifier used in environment.yml
     */
    String value();
}