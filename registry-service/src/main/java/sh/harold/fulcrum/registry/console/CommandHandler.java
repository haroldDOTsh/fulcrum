package sh.harold.fulcrum.registry.console;

/**
 * Interface for handling console commands
 */
public interface CommandHandler {
    /**
     * Execute the command with given arguments
     *
     * @param args Command arguments (first element is the command name itself)
     * @return true if command executed successfully
     */
    boolean execute(String[] args);

    /**
     * Get the command name
     *
     * @return The primary command name
     */
    String getName();

    /**
     * Get command aliases
     *
     * @return Array of alternative command names
     */
    String[] getAliases();

    /**
     * Get command description
     *
     * @return Description of what the command does
     */
    String getDescription();

    /**
     * Get command usage
     *
     * @return Usage syntax for the command
     */
    String getUsage();
}