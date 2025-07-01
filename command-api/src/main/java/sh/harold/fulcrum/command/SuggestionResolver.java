package sh.harold.fulcrum.command;

import sh.harold.fulcrum.command.annotations.Suggestions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class SuggestionResolver {
    private static final Logger LOGGER = Logger.getLogger(SuggestionResolver.class.getName());

    private SuggestionResolver() {}

    /**
     * Resolves a suggestion provider for a command argument field, using only Paper API and Adventure Audience.
     * The suggestion method should accept either no arguments (static) or an Audience (dynamic).
     */
    public static SuggestionProviderAdapter resolve(Field field, Object executor) {
        Suggestions suggestions = field.getAnnotation(Suggestions.class);
        Class<?> type = field.getType();
        if (suggestions == null) {
            // No suggestions specified
            return null;
        }
        String methodName = suggestions.value();
        boolean dynamic = suggestions.dynamic();
        try {
            Method method;
            if (dynamic) {
                // Dynamic: method(Audience)
                method = executor.getClass().getMethod(methodName, Audience.class);
            } else {
                // Static: method()
                method = executor.getClass().getMethod(methodName);
            }
            if (dynamic) {
                return (ctx, builder) -> {
                    // CommandSourceStack is the Paper context for Brigadier
                    CommandSourceStack sourceStack = (CommandSourceStack) ctx.getSource();
                    Audience audience = (Audience) sourceStack.getSender();
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> result = (List<String>) method.invoke(executor, audience);
                        result.forEach(builder::suggest);
                        return builder.buildFuture();
                    } catch (Exception e) {
                        LOGGER.warning("Failed to invoke dynamic suggestions method: " + methodName + ": " + e);
                        return builder.buildFuture();
                    }
                };
            } else {
                @SuppressWarnings("unchecked")
                List<String> staticList = (List<String>) method.invoke(executor);
                return (ctx, builder) -> {
                    staticList.forEach(builder::suggest);
                    return builder.buildFuture();
                };
            }
        } catch (NoSuchMethodException e) {
            LOGGER.warning("No such method for suggestions: " + methodName + " on " + executor.getClass());
        } catch (Exception e) {
            LOGGER.warning("Failed to resolve suggestions for " + field.getName() + ": " + e);
        }
        return null;
    }

    /**
     * Library-agnostic suggestion resolver for testing (static only, no Bukkit).
     * Only supports static suggestion methods or enum fallback.
     */
    static List<String> resolveValues(Field field, Object executor) {
        Suggestions suggestions = field.getAnnotation(Suggestions.class);
        Class<?> type = field.getType();
        if (suggestions != null) {
            String methodName = suggestions.value();
            boolean dynamic = suggestions.dynamic();
            try {
                Method method;
                if (dynamic) {
                    // For test, just pass null as Audience
                    method = executor.getClass().getMethod(methodName, Audience.class);
                    @SuppressWarnings("unchecked")
                    List<String> result = (List<String>) method.invoke(executor, (Object) null);
                    return result;
                } else {
                    method = executor.getClass().getMethod(methodName);
                    @SuppressWarnings("unchecked")
                    List<String> result = (List<String>) method.invoke(executor);
                    return result;
                }
            } catch (NoSuchMethodException e) {
                // fall through to enum fallback
            } catch (Exception e) {
                return null;
            }
        }
        // Enum fallback
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            List<String> names = new ArrayList<>();
            for (Object constant : constants) {
                names.add(constant.toString());
            }
            return names;
        }
        return null;
    }
}
