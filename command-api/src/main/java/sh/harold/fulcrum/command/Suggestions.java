package sh.harold.fulcrum.command;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Suggestions {
    String value();                   // Method name
    boolean dynamic() default false; // If true, call on each tab-complete
}
