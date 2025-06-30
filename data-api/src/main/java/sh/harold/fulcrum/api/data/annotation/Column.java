package sh.harold.fulcrum.api.data.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    String value() default "";

    boolean primary() default false;

    PrimaryKeyGeneration generation() default PrimaryKeyGeneration.NONE;
}
