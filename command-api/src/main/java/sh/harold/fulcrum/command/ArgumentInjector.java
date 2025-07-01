package sh.harold.fulcrum.command;

import sh.harold.fulcrum.command.annotations.Argument;

/**
 * Injects command arguments into fields annotated with @Argument in a CommandExecutor.
 */
public final class ArgumentInjector {
    private ArgumentInjector() {
    }

    public static void inject(CommandContext ctx, CommandExecutor executor) {
        java.util.Objects.requireNonNull(ctx);
        java.util.Objects.requireNonNull(executor);
        Class<?> clazz = executor.getClass();
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                var arg = field.getAnnotation(Argument.class);
                if (arg == null) continue;
                Object value = switch (field.getType().getName()) {
                    case "java.lang.String" -> ctx.argument(arg.value(), String.class);
                    case "int", "java.lang.Integer" -> ctx.argument(arg.value(), Integer.class);
                    case "org.bukkit.entity.Player" -> ctx.argument(arg.value(), org.bukkit.entity.Player.class);
                    default -> null;
                };
                if (value != null) {
                    boolean wasAccessible = field.canAccess(executor);
                    try {
                        field.setAccessible(true);
                        field.set(executor, value);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to inject argument: " + arg.value(), e);
                    } finally {
                        field.setAccessible(wasAccessible);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
}
