package sh.harold.fulcrum.registry.console.commands;

import sh.harold.fulcrum.registry.console.CommandHandler;

/**
 * Command to clear the console screen
 */
public class ClearCommand implements CommandHandler {
    
    @Override
    public boolean execute(String[] args) {
        // ANSI escape codes to clear screen
        System.out.print("\033[H\033[2J");
        System.out.flush();
        
        // For Windows compatibility
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            }
        } catch (Exception e) {
            // Fallback: print many newlines
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
        
        return true;
    }
    
    @Override
    public String getName() {
        return "clear";
    }
    
    @Override
    public String[] getAliases() {
        return new String[] {"cls"};
    }
    
    @Override
    public String getDescription() {
        return "Clear the console screen";
    }
    
    @Override
    public String getUsage() {
        return "clear";
    }
}