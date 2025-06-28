package sh.harold.fulcrum.api.message.example;

import net.kyori.adventure.text.Component;
import sh.harold.fulcrum.api.message.Message;

import java.util.UUID;

/**
 * Example usage of the styled messaging system with chaining support.
 * Demonstrates both direct messaging and tagged message chaining.
 */
public class MessageExample {
      /**
     * Example of how to use the new styled messaging system with chaining.
     */
    public void demonstrateChainedUsage() {
        UUID playerId = UUID.randomUUID(); // In real use, get this from the player
        
        // CHAINED MESSAGES WITH TAGS
        // These messages will be sent with tags prepended
        
        Message.success("banking.deposit.success", 1000, "coins")
               .staff().system().send(playerId);
        // Results in: &c[STAFF]&r &5[DAEMON]&r &aSuccessfully deposited &e1000 &acoins!
        
        // Results in: &d[EVENT]&r &6[VIP]&r &e[PREMIUM]&r &aThe &ePvP Tournament &ahas started!
        
        // BUILDING MESSAGES WITHOUT SENDING (for logging, etc.)
        Component message = Message.info("player.stats.summary", "kills", 42)
                               .staff().system().get(playerId);
        // Gets the formatted string without sending it
        
        // MESSAGES WITHOUT TAGS (traditional usage)
        Message.success("operation.completed").send(playerId);
        // Results in: &aOperation completed!
    }
    
    /**
     * Example showing the namespacing format.
     * 
     * Translation keys follow the format: feature.detail.detail
     * 
     * Examples:
     * - "banking.deposit.success" -> /plugins/internal-core/lang/banking/en_us.yml
     * - "fairy_soul.collection.complete" -> /plugins/internal-core/lang/fairy_soul/en_us.yml  
     * - "player.balance.current" -> /plugins/internal-core/lang/player/en_us.yml
     * - "server.restart.warning" -> /plugins/internal-core/lang/server/en_us.yml
     * 
     * The system automatically:
     * 1. Creates the feature directory if it doesn't exist
     * 2. Creates the locale file (en_us.yml) if it doesn't exist
     * 3. Populates it with placeholder messages if keys are missing
     * 4. Applies the appropriate color styling based on message type
     * 5. Converts legacy color codes to Adventure format for modern clients
     */
    public void explainNamespacing() {
        // This would create the following file structure:
        // /plugins/internal-core/lang/
        //   ├── banking/
        //   │   └── en_us.yml
        //   ├── fairy_soul/
        //   │   └── en_us.yml
        //   ├── player/
        //   │   └── en_us.yml
        //   └── server/
        //       └── en_us.yml
        
        // Inside banking/en_us.yml:
        // deposit:
        //   success: "Successfully deposited {0} {1}!"
        
        // Inside fairy_soul/en_us.yml:
        // collection:
        //   complete: "Successfully collected {1} fairy souls from {0}!"
        
        // Inside player/en_us.yml:
        // balance:
        //   current: "Your current balance is {0} coins"
        
        // Inside server/en_us.yml:
        // restart:
        //   warning: "Server restart in {0} minutes!"
    }
}
