package sh.harold.fulcrum.registry.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing console commands
 */
public class CommandRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRegistry.class);
    
    private final Map<String, CommandHandler> commands = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    
    /**
     * Register a command handler
     * @param handler The command handler to register
     */
    public void registerCommand(CommandHandler handler) {
        String name = handler.getName().toLowerCase();
        commands.put(name, handler);
        
        // Register aliases
        for (String alias : handler.getAliases()) {
            aliases.put(alias.toLowerCase(), name);
        }
        
        LOGGER.debug("Registered command: {} with {} aliases", name, handler.getAliases().length);
    }
    
    /**
     * Register a command handler with explicit name
     * @param name The command name
     * @param handler The command handler to register
     */
    public void register(String name, CommandHandler handler) {
        commands.put(name.toLowerCase(), handler);
        
        // Register aliases
        for (String alias : handler.getAliases()) {
            aliases.put(alias.toLowerCase(), name.toLowerCase());
        }
        
        LOGGER.debug("Registered command: {} with {} aliases", name, handler.getAliases().length);
    }
    
    /**
     * Execute a command
     * @param input The full command input
     * @return true if command executed successfully
     */
    public boolean executeCommand(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }
        
        String[] parts = input.trim().split("\\s+");
        String commandName = parts[0].toLowerCase();
        
        // Check for alias
        if (aliases.containsKey(commandName)) {
            commandName = aliases.get(commandName);
        }
        
        CommandHandler handler = commands.get(commandName);
        if (handler == null) {
            System.out.println("Unknown command: " + parts[0]);
            System.out.println("Type 'help' for available commands");
            return false;
        }
        
        try {
            return handler.execute(parts);
        } catch (Exception e) {
            LOGGER.error("Error executing command: " + commandName, e);
            System.out.println("Error executing command: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get all registered commands
     * @return Collection of all command handlers
     */
    public Collection<CommandHandler> getAllCommands() {
        return commands.values();
    }
    
    /**
     * Get a specific command handler
     * @param name The command name or alias
     * @return The command handler, or null if not found
     */
    public CommandHandler getCommand(String name) {
        name = name.toLowerCase();
        if (aliases.containsKey(name)) {
            name = aliases.get(name);
        }
        return commands.get(name);
    }
}