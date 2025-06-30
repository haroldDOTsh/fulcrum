package sh.harold.fulcrum.command;

import java.lang.annotation.*;

/**
 * Marks a field in a CommandExecutor as a command argument to be injected.
 * Only supported types will be injected (String, int, Player).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Argument {
    String value();
}
