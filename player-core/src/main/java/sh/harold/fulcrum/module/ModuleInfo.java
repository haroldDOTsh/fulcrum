package sh.harold.fulcrum.module;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation for external plugin modules.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ModuleInfo {
    String name();
    String description() default "";
    String[] authors() default {};
    String version() default "1.0.0";
}
