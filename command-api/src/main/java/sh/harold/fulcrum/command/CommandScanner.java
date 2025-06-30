package sh.harold.fulcrum.command;

import sh.harold.fulcrum.command.annotations.*;
import java.util.*;
import java.util.stream.Collectors;

public final class CommandScanner {
    private CommandScanner() {}

    public static List<CommandDefinition> scan(Set<Class<?>> classes) {
        return classes.stream()
            .filter(clazz -> clazz.isAnnotationPresent(Command.class))
            .filter(CommandExecutor.class::isAssignableFrom)
            .map(clazz -> {
                var command = clazz.getAnnotation(Command.class);
                var aliases = clazz.isAnnotationPresent(Aliases.class)
                        ? clazz.getAnnotation(Aliases.class).value()
                        : new String[0];
                var rank = clazz.isAnnotationPresent(Rank.class)
                        ? clazz.getAnnotation(Rank.class).value()
                        : RankLevel.PLAYER;
                @SuppressWarnings("unchecked")
                Class<? extends CommandExecutor> impl = clazz.asSubclass(CommandExecutor.class);
                return new CommandDefinition(command.value(), aliases, rank, impl);
            })
            .collect(Collectors.toList());
    }

    public record CommandDefinition(
        String name,
        String[] aliases,
        RankLevel requiredRank,
        Class<? extends CommandExecutor> implementationClass
    ) {}
}
