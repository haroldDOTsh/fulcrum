package sh.harold.fulcrum.command;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class SuggestionResolver {
    private static final Logger LOGGER = Logger.getLogger(SuggestionResolver.class.getName());

    private SuggestionResolver() {
    }

    public static SuggestionProviderAdapter resolve(Field field, Object executor) {
        sh.harold.fulcrum.command.Suggestions suggestions = field.getAnnotation(sh.harold.fulcrum.command.Suggestions.class);
        Class<?> type = field.getType();
        if (suggestions == null) {
            // Fallback for known types is handled in player-core, so just return null here
            return null;
        }
        // Custom suggestions
        String methodName = suggestions.value();
        boolean dynamic = suggestions.dynamic();
        try {
            java.lang.reflect.Method method;
            if (dynamic) {
                method = executor.getClass().getMethod(methodName, org.bukkit.command.CommandSender.class);
            } else {
                method = executor.getClass().getMethod(methodName);
            }
            if (dynamic) {
                return (ctx, builder) -> {
                    org.bukkit.command.CommandSender sender = (org.bukkit.command.CommandSender) ctx.getSource();
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.List<String> result = (java.util.List<String>) method.invoke(executor, sender);
                        result.forEach(builder::suggest);
                        return builder.buildFuture();
                    } catch (Exception e) {
                        LOGGER.warning("Failed to invoke dynamic suggestions method: " + methodName + ": " + e);
                        return builder.buildFuture();
                    }
                };
            } else {
                @SuppressWarnings("unchecked")
                java.util.List<String> staticList = (java.util.List<String>) method.invoke(executor);
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

    // Library-agnostic suggestion resolver for testing
    static List<String> resolveValues(Field field, Object executor) {
        sh.harold.fulcrum.command.Suggestions suggestions = field.getAnnotation(sh.harold.fulcrum.command.Suggestions.class);
        Class<?> type = field.getType();
        if (suggestions != null) {
            String methodName = suggestions.value();
            boolean dynamic = suggestions.dynamic();
            try {
                Method method;
                if (dynamic) {
                    method = executor.getClass().getMethod(methodName, org.bukkit.command.CommandSender.class);
                    // For test, just pass null as sender
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
