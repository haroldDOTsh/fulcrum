package sh.harold.fulcrum.minigame;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata descriptor for Fulcrum minigame modules.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MinigameInfo {
    String name();

    String creator() default "";

    String releaseDate() default "";

    String description() default "";

    String[] tags() default {};
}
