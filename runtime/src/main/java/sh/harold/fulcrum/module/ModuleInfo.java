package sh.harold.fulcrum.module;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation for external plugin modules.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleInfo {
    String name();

    String[] dependsOn() default {};

    String description() default "";
}
