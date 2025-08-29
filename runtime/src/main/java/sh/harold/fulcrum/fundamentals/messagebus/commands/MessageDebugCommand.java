package sh.harold.fulcrum.fundamentals.messagebus.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.messagebus.MessageBus;
import sh.harold.fulcrum.api.messagebus.MessageEnvelope;
import sh.harold.fulcrum.api.messagebus.MessageHandler;
import sh.harold.fulcrum.api.messagebus.messages.*;
import sh.harold.fulcrum.api.messagebus.impl.RedisMessageBus;
import sh.harold.fulcrum.api.messagebus.impl.InMemoryMessageBus;
import sh.harold.fulcrum.api.messagebus.impl.AbstractMessageBus;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.lifecycle.DependencyContainer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * Debug command for simulating receiving messages locally for testing purposes.
 * This allows developers to test message handlers without needing actual network messages.
 */
public class MessageDebugCommand {
    private static final Logger LOGGER = Logger.getLogger(MessageDebugCommand.class.getName());
    private final DependencyContainer container;
    private final ObjectMapper objectMapper;
    
    // Map of predefined message types and their constructors
    private final Map<String, MessageFactory> predefinedTypes = new HashMap<>();
    
    public MessageDebugCommand(DependencyContainer container) {
        this.container = container;
        this.objectMapper = new ObjectMapper();
        
        // Register predefined message types
        registerPredefinedTypes();
    }
    
    /**
     * Build the command node for registration.
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return literal("fulcrum")
            .requires(source -> RankUtils.isAdmin(source.getSender()))
            .then(literal("messagedebug")
                .then(literal("simulate")
                    .then(argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            predefinedTypes.keySet().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(this::simulatePredefined)
                        .then(argument("params", StringArgumentType.greedyString())
                            .executes(this::simulatePredefinedWithParams))))
                .then(literal("raw")
                    .then(argument("type", StringArgumentType.word())
                        .then(argument("json", StringArgumentType.greedyString())
                            .executes(this::simulateRaw))))
                .then(literal("list")
                    .executes(this::listTypes))
                .executes(this::showHelp))
            .then(literal("msgdebug") // Alias
                .redirect(literal("messagedebug").build()))
            .build();
    }
    
    private void registerPredefinedTypes() {
        // Server lifecycle messages
        predefinedTypes.put("heartbeat", params -> {
            ServerIdentifier serverIdentifier = container.get(ServerIdentifier.class);
            ServerHeartbeatMessage msg = new ServerHeartbeatMessage(
                serverIdentifier.getServerId(),
                serverIdentifier.getType()
            );
            msg.setTps(20.0);
            msg.setPlayerCount(getIntParam(params, "players", 0));
            msg.setMaxCapacity(getIntParam(params, "capacity", 100));
            msg.setUptime(System.currentTimeMillis());
            msg.setRole(serverIdentifier.getRole());
            return msg;
        });
        
        predefinedTypes.put("proxy-announce", params -> {
            int capacity = getIntParam(params, "capacity", 100);
            int softCap = getIntParam(params, "soft", capacity / 2);
            return new ProxyAnnouncementMessage(
                "fulcrum-proxy-1",
                1,
                capacity,
                softCap,
                getIntParam(params, "players", 0)
            );
        });
        
        predefinedTypes.put("proxy-discover", params -> {
            ServerIdentifier serverIdentifier = container.get(ServerIdentifier.class);
            return new ProxyDiscoveryRequest(
                serverIdentifier.getServerId(),
                serverIdentifier.getType()
            );
        });
        
        predefinedTypes.put("server-register", params -> {
            ServerIdentifier serverIdentifier = container.get(ServerIdentifier.class);
            ServerRegistrationRequest request = new ServerRegistrationRequest(
                serverIdentifier.getServerId(),
                serverIdentifier.getType(),
                serverIdentifier.getHardCap()
            );
            request.setAddress(serverIdentifier.getAddress());
            request.setPort(serverIdentifier.getPort());
            request.setRole(serverIdentifier.getRole());
            return request;
        });
        
        // Placeholder for future game-specific messages
        predefinedTypes.put("provision-game", params -> {
            Map<String, Object> gameData = new HashMap<>();
            gameData.put("gameType", params.getOrDefault("gameType", "bedwars"));
            gameData.put("mode", params.getOrDefault("mode", "4v4"));
            gameData.put("map", params.getOrDefault("map", "default"));
            return gameData;
        });
    }
    
    private int simulatePredefined(CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "type");
        return simulateMessage(ctx.getSource().getSender(), type, new HashMap<>());
    }
    
    private int simulatePredefinedWithParams(CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "type");
        String paramString = StringArgumentType.getString(ctx, "params");
        
        // Parse parameters (format: --key value --key2 value2)
        Map<String, String> params = parseParams(paramString);
        
        return simulateMessage(ctx.getSource().getSender(), type, params);
    }
    
    private int simulateRaw(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String type = StringArgumentType.getString(ctx, "type");
        String json = StringArgumentType.getString(ctx, "json");
        
        try {
            // Parse JSON into an object
            JsonNode payload = objectMapper.readTree(json);
            
            // Create envelope and simulate reception
            MessageEnvelope envelope = createEnvelope(type, payload);
            int handlersTriggered = triggerHandlers(envelope);
            
            sender.sendMessage(Component.text("✓ Simulated raw message reception", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("  Type: ", NamedTextColor.GRAY)
                .append(Component.text(type, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Handlers triggered: ", NamedTextColor.GRAY)
                .append(Component.text(handlersTriggered, NamedTextColor.YELLOW)));
            
            return 1;
        } catch (Exception e) {
            sender.sendMessage(Component.text("✗ Failed to parse JSON: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
    }
    
    private int listTypes(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        
        sender.sendMessage(Component.text("\n=== Available Message Types ===", NamedTextColor.GOLD));
        
        sender.sendMessage(Component.text("\nPredefined Types:", NamedTextColor.YELLOW));
        predefinedTypes.keySet().stream().sorted().forEach(type -> {
            sender.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                .append(Component.text(type, NamedTextColor.WHITE)));
        });
        
        sender.sendMessage(Component.text("\nCurrently Subscribed Types:", NamedTextColor.YELLOW));
        Set<String> subscribedTypes = getSubscribedTypes();
        if (subscribedTypes.isEmpty()) {
            sender.sendMessage(Component.text("  (none)", NamedTextColor.GRAY));
        } else {
            subscribedTypes.stream().sorted().forEach(type -> {
                sender.sendMessage(Component.text("  • ", NamedTextColor.GRAY)
                    .append(Component.text(type, NamedTextColor.AQUA)));
            });
        }
        
        sender.sendMessage(Component.text("\nUse /fulcrum msgdebug simulate <type> to test", NamedTextColor.GRAY));
        
        return 1;
    }
    
    private int showHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        
        sender.sendMessage(Component.text("\n=== Message Debug Command ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Simulate receiving messages locally for testing", NamedTextColor.GRAY));
        
        sender.sendMessage(Component.text("\nCommands:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /fulcrum msgdebug simulate <type> [params]", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("    Simulate a predefined message type", NamedTextColor.GRAY));
        
        sender.sendMessage(Component.text("  /fulcrum msgdebug raw <type> <json>", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("    Simulate a custom JSON message", NamedTextColor.GRAY));
        
        sender.sendMessage(Component.text("  /fulcrum msgdebug list", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("    List available message types", NamedTextColor.GRAY));
        
        sender.sendMessage(Component.text("\nExamples:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /fulcrum msgdebug simulate heartbeat", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  /fulcrum msgdebug simulate proxy-announce --capacity 200", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  /fulcrum msgdebug raw provision-game {\"gameType\":\"bedwars\"}", NamedTextColor.AQUA));
        
        return 1;
    }
    
    private int simulateMessage(CommandSender sender, String type, Map<String, String> params) {
        MessageFactory factory = predefinedTypes.get(type);
        if (factory == null) {
            sender.sendMessage(Component.text("✗ Unknown message type: " + type, NamedTextColor.RED));
            sender.sendMessage(Component.text("  Use /fulcrum msgdebug list to see available types", NamedTextColor.GRAY));
            return 0;
        }
        
        try {
            // Create the message
            Object message = factory.create(params);
            
            // Convert to JSON for the envelope
            JsonNode payload = objectMapper.valueToTree(message);
            
            // Create envelope and simulate reception
            MessageEnvelope envelope = createEnvelope(type, payload);
            int handlersTriggered = triggerHandlers(envelope);
            
            sender.sendMessage(Component.text("✓ Simulated message reception", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("  Type: ", NamedTextColor.GRAY)
                .append(Component.text(type, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("  Sender: ", NamedTextColor.GRAY)
                .append(Component.text("debug-simulation", NamedTextColor.DARK_GRAY)));
            sender.sendMessage(Component.text("  Handlers triggered: ", NamedTextColor.GRAY)
                .append(Component.text(handlersTriggered, NamedTextColor.YELLOW)));
            
            if (!params.isEmpty()) {
                sender.sendMessage(Component.text("  Parameters: ", NamedTextColor.GRAY)
                    .append(Component.text(params.toString(), NamedTextColor.DARK_GRAY)));
            }
            
            return 1;
        } catch (Exception e) {
            sender.sendMessage(Component.text("✗ Failed to simulate message: " + e.getMessage(), NamedTextColor.RED));
            LOGGER.warning("Failed to simulate message: " + e);
            e.printStackTrace();
            return 0;
        }
    }
    
    private MessageEnvelope createEnvelope(String type, JsonNode payload) {
        ServerIdentifier serverIdentifier = container.get(ServerIdentifier.class);
        String targetId = serverIdentifier != null ? serverIdentifier.getServerId() : "unknown";
        
        return new MessageEnvelope(
            type,
            "debug-simulation",
            targetId,
            UUID.randomUUID(),
            System.currentTimeMillis(),
            1,
            payload
        );
    }
    
    private int triggerHandlers(MessageEnvelope envelope) {
        MessageBus messageBus = container.get(MessageBus.class);
        if (messageBus == null) {
            LOGGER.warning("MessageBus not available in container");
            return 0;
        }
        
        // Get the subscriptions map using reflection
        try {
            Field subscriptionsField = null;
            
            // Check if it's RedisMessageBus, InMemoryMessageBus or AbstractMessageBus
            if (messageBus instanceof RedisMessageBus) {
                subscriptionsField = AbstractMessageBus.class.getDeclaredField("subscriptions");
            } else if (messageBus instanceof InMemoryMessageBus) {
                subscriptionsField = AbstractMessageBus.class.getDeclaredField("subscriptions");
            } else if (messageBus instanceof AbstractMessageBus) {
                subscriptionsField = AbstractMessageBus.class.getDeclaredField("subscriptions");
            } else {
                // Try generic approach
                subscriptionsField = messageBus.getClass().getDeclaredField("subscriptions");
            }
            
            if (subscriptionsField != null) {
                subscriptionsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, List<MessageHandler>> subscriptions = 
                    (Map<String, List<MessageHandler>>) subscriptionsField.get(messageBus);
                
                List<MessageHandler> handlers = subscriptions.get(envelope.getType());
                if (handlers != null && !handlers.isEmpty()) {
                    for (MessageHandler handler : handlers) {
                        try {
                            handler.handle(envelope);
                        } catch (Exception e) {
                            LOGGER.warning("Error in handler for type " + envelope.getType() + ": " + e);
                        }
                    }
                    return handlers.size();
                }
            }
            
            // Also try to call handleMessage directly if it exists
            try {
                Method handleMethod = messageBus.getClass().getDeclaredMethod("handleMessage", MessageEnvelope.class);
                handleMethod.setAccessible(true);
                handleMethod.invoke(messageBus, envelope);
                return 1; // At least one handler method was called
            } catch (NoSuchMethodException e) {
                // Method doesn't exist, that's ok
            }
            
        } catch (Exception e) {
            LOGGER.warning("Failed to trigger handlers via reflection: " + e);
            e.printStackTrace();
        }
        
        return 0;
    }
    
    private Set<String> getSubscribedTypes() {
        Set<String> types = new HashSet<>();
        MessageBus messageBus = container.get(MessageBus.class);
        
        if (messageBus == null) {
            return types;
        }
        
        try {
            Field subscriptionsField = null;
            
            if (messageBus instanceof RedisMessageBus || messageBus instanceof InMemoryMessageBus) {
                // Both extend AbstractMessageBus which has the subscriptions field
                subscriptionsField = AbstractMessageBus.class.getDeclaredField("subscriptions");
            } else if (messageBus instanceof AbstractMessageBus) {
                subscriptionsField = AbstractMessageBus.class.getDeclaredField("subscriptions");
            } else {
                subscriptionsField = messageBus.getClass().getDeclaredField("subscriptions");
            }
            
            if (subscriptionsField != null) {
                subscriptionsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ?> subscriptions = (Map<String, ?>) subscriptionsField.get(messageBus);
                types.addAll(subscriptions.keySet());
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to get subscribed types: " + e);
        }
        
        return types;
    }
    
    private Map<String, String> parseParams(String paramString) {
        Map<String, String> params = new HashMap<>();
        String[] parts = paramString.split("\\s+--");
        
        for (String part : parts) {
            if (part.isEmpty()) continue;
            
            String[] keyValue = part.split("\\s+", 2);
            if (keyValue.length >= 1) {
                String key = keyValue[0].replaceFirst("^--", "");
                String value = keyValue.length > 1 ? keyValue[1] : "true";
                params.put(key, value);
            }
        }
        
        return params;
    }
    
    private int getIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Fall through to default
            }
        }
        return defaultValue;
    }
    
    @FunctionalInterface
    private interface MessageFactory {
        Object create(Map<String, String> params) throws Exception;
    }
}