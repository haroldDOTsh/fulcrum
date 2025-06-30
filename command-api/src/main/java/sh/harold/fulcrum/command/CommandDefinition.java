package sh.harold.fulcrum.command;

public record CommandDefinition(
        String name,
        String[] aliases,
        Class<? extends CommandExecutor> implementationClass
) {
}
