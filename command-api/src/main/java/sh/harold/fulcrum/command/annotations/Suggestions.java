package sh.harold.fulcrum.command.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Suggestions {
    String value();                   // Method name

    boolean dynamic() default false; // If true, call on each tab-complete
}
