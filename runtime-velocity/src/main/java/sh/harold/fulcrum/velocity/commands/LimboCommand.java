package sh.harold.fulcrum.velocity.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.data.DataAPI;
import sh.harold.fulcrum.velocity.api.rank.Rank;
import sh.harold.fulcrum.velocity.api.rank.VelocityRankUtils;
import sh.harold.fulcrum.velocity.api.ServerIdentifier;
import sh.harold.fulcrum.velocity.fundamentals.lifecycle.VelocityServerLifecycleFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Command to send players to the limbo server.
 * Usage:
 * - /limbo - sends the sender to limbo
 * - /limbo &lt;player&gt; - sends another player to limbo (admin only)
 */
public class LimboCommand implements SimpleCommand {
    
    private final ProxyServer proxy;
    private final Logger logger;
    private final DataAPI dataAPI;
    private final VelocityServerLifecycleFeature lifecycleFeature;
    
    public LimboCommand(ProxyServer proxy, Logger logger, DataAPI dataAPI,
                       VelocityServerLifecycleFeature lifecycleFeature) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataAPI = dataAPI;
        this.lifecycleFeature = lifecycleFeature;
    }
    
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        
        // Handle async rank checking
        CompletableFuture.runAsync(() -> {
            try {
                if (args.length == 0) {
                    // Self-limbo: /limbo
                    if (!(source instanceof Player)) {
                        source.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
                        return;
                    }
                    
                    Player player = (Player) source;
                    sendToLimbo(player, player);
                    
                } else if (args.length == 1) {
                    // Send another player to limbo: /limbo <player>
                    // Check if sender has admin permission
                    VelocityRankUtils.isAdmin(source, dataAPI, logger).thenAccept(isAdmin -> {
                        if (!isAdmin) {
                            source.sendMessage(Component.text("You need ADMIN rank to send other players to limbo!", NamedTextColor.RED));
                            return;
                        }
                        
                        // Find target player
                        String targetName = args[0];
                        Optional<Player> targetPlayer = proxy.getPlayer(targetName);
                        
                        if (!targetPlayer.isPresent()) {
                            source.sendMessage(Component.text("Player '" + targetName + "' not found!", NamedTextColor.RED));
                            return;
                        }
                        
                        sendToLimbo(source, targetPlayer.get());
                    }).exceptionally(ex -> {
                        logger.error("Error checking admin permission for limbo command", ex);
                        source.sendMessage(Component.text("An error occurred while checking permissions!", NamedTextColor.RED));
                        return null;
                    });
                    
                } else {
                    source.sendMessage(Component.text("Usage: /limbo [player]", NamedTextColor.RED));
                }
            } catch (Exception e) {
                logger.error("Error executing limbo command", e);
                source.sendMessage(Component.text("An error occurred while executing the command!", NamedTextColor.RED));
            }
        });
    }
    
    /**
     * Send a player to the limbo server
     */
    private void sendToLimbo(CommandSource sender, Player target) {
        try {
            // Find limbo server - look for servers with role "fallback" or type "LIMBO"
            RegisteredServer limboServer = findLimboServer();
            
            if (limboServer == null) {
                sender.sendMessage(Component.text("No limbo server is currently available!", NamedTextColor.RED));
                logger.warn("No limbo server found when attempting to send {} to limbo", target.getUsername());
                return;
            }
            
            // Create connection request to limbo server
            target.createConnectionRequest(limboServer)
                .connect()
                .thenAccept(result -> {
                    if (result.isSuccessful()) {
                        // Success message
                        if (sender.equals(target)) {
                            sender.sendMessage(Component.text("You have been sent to limbo!", NamedTextColor.GREEN));
                        } else {
                            sender.sendMessage(Component.text("Successfully sent " + target.getUsername() + " to limbo!", NamedTextColor.GREEN));
                            target.sendMessage(Component.text("You have been sent to limbo by " + getSenderName(sender) + "!", NamedTextColor.YELLOW));
                        }
                        logger.info("{} sent {} to limbo server: {}", 
                                   getSenderName(sender), target.getUsername(), limboServer.getServerInfo().getName());
                    } else {
                        // Failed to connect
                        Optional<Component> reason = result.getReasonComponent();
                        if (reason.isPresent()) {
                            sender.sendMessage(Component.text("Failed to send to limbo: ", NamedTextColor.RED)
                                .append(reason.get()));
                        } else {
                            sender.sendMessage(Component.text("Failed to send to limbo!", NamedTextColor.RED));
                        }
                        logger.warn("Failed to send {} to limbo server: {}", 
                                   target.getUsername(), limboServer.getServerInfo().getName());
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(Component.text("An error occurred while connecting to limbo!", NamedTextColor.RED));
                    logger.error("Error sending {} to limbo", target.getUsername(), ex);
                    return null;
                });
                
        } catch (Exception e) {
            sender.sendMessage(Component.text("An error occurred while sending to limbo!", NamedTextColor.RED));
            logger.error("Error in sendToLimbo for player {}", target.getUsername(), e);
        }
    }
    
    /**
     * Find the limbo server from registered servers
     * Looks for servers with role "fallback" or type "LIMBO"
     */
    private RegisteredServer findLimboServer() {
        // First, try to find a server with role "fallback" using lifecycle feature
        if (lifecycleFeature != null) {
            for (ServerIdentifier server : lifecycleFeature.getServersByRole("fallback")) {
                Optional<RegisteredServer> registered = proxy.getServer(server.getServerId());
                if (registered.isPresent()) {
                    logger.debug("Found limbo server with fallback role: {}", server.getServerId());
                    return registered.get();
                }
            }
            
            // Also check for "limbo" role
            for (ServerIdentifier server : lifecycleFeature.getServersByRole("limbo")) {
                Optional<RegisteredServer> registered = proxy.getServer(server.getServerId());
                if (registered.isPresent()) {
                    logger.debug("Found limbo server with limbo role: {}", server.getServerId());
                    return registered.get();
                }
            }
        }
        
        // Fallback: look for any server with "limbo" in the name
        for (RegisteredServer server : proxy.getAllServers()) {
            String serverName = server.getServerInfo().getName().toLowerCase();
            if (serverName.contains("limbo") || serverName.contains("fallback")) {
                logger.debug("Found limbo server by name pattern: {}", server.getServerInfo().getName());
                return server;
            }
        }
        
        logger.warn("No limbo server found!");
        return null;
    }
    
    /**
     * Get display name for command sender
     */
    private String getSenderName(CommandSource sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getUsername();
        }
        return "Console";
    }
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        // Everyone can use /limbo to send themselves
        // Admin check is done inside execute() for sending others
        return true;
    }
    
    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        
        // Only suggest player names for the first argument
        if (args.length == 0 || args.length == 1) {
            String partial = args.length == 1 ? args[0].toLowerCase() : "";
            
            // Only suggest if sender is admin (async check, so we'll return all for now)
            // In a real implementation, you might want to cache admin status
            return proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .sorted()
                .collect(Collectors.toList());
        }
        
        return new ArrayList<>();
    }
}