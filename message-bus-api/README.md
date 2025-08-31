# Message Bus API

Inter-server messaging system for distributed Minecraft networks.

## Overview

Provides server-to-server communication with:
- Broadcast messages to all servers
- Targeted messages to specific servers
- Request-response patterns with timeouts
- Type-safe message handling

## Quick Start

### 1. Define Your Message

```java
public record BroadcastMessage(
    String message,
    String sender,
    MessageType type
) {
    public enum MessageType {
        INFO, WARNING, ALERT
    }
}
```

### 2. Register Message Type

```java
CodecRegistry codec = new CodecRegistry();
codec.register("broadcast.global", BroadcastMessage.class);
```

### 3. Subscribe to Messages

```java
messageBus.subscribe("broadcast.global", envelope -> {
    BroadcastMessage msg = codec.deserialize(
        envelope.getType(), 
        envelope.getPayload(), 
        BroadcastMessage.class
    );
    // Handle the message
    Bukkit.broadcast(Component.text(msg.message()));
});
```

## Tutorial: Custom Messages

### Example 1: Global Broadcast Command

**Message Definition:**
```java
public record GlobalBroadcast(
    String message,
    String senderName,
    String senderServer,
    long timestamp
) {}
```

**Backend Server (Sender):**
```java
public class BroadcastCommand {
    private final MessageBus messageBus;
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        String message = String.join(" ", args);
        
        GlobalBroadcast broadcast = new GlobalBroadcast(
            message,
            sender.getName(),
            Bukkit.getServer().getName(),
            System.currentTimeMillis()
        );
        
        // Send to all servers
        messageBus.broadcast("broadcast.global", broadcast);
        sender.sendMessage("§aBroadcast sent to all servers!");
    }
}
```

**All Servers (Receivers):**
```java
public class BroadcastHandler {
    public void register(MessageBus messageBus) {
        messageBus.subscribe("broadcast.global", envelope -> {
            GlobalBroadcast msg = codec.deserialize(
                envelope.getType(),
                envelope.getPayload(),
                GlobalBroadcast.class
            );
            
            // Display to all players
            String formatted = String.format(
                "§6[Network] §e%s §7(%s): §f%s",
                msg.senderName(),
                msg.senderServer(),
                msg.message()
            );
            
            Bukkit.getOnlinePlayers().forEach(player -> 
                player.sendMessage(formatted)
            );
        });
    }
}
```

### Example 2: Server Shutdown with Registry

**Message Definitions:**
```java
public record ShutdownRequest(
    String targetServer,
    String reason,
    int delaySeconds,
    String initiator
) {}

public record ShutdownResponse(
    String serverId,
    boolean accepted,
    String message
) {}
```

**Proxy (Command Initiator):**
```java
public class ShutdownCommand {
    private final MessageBus messageBus;
    private final ServerRegistry registry;
    
    public void execute(CommandSender sender, String serverId, int delay) {
        // Check if server exists in registry
        if (!registry.hasServer(serverId)) {
            sender.sendMessage("§cServer not found in registry!");
            return;
        }
        
        ShutdownRequest request = new ShutdownRequest(
            serverId,
            "Admin initiated shutdown",
            delay,
            sender.getName()
        );
        
        // Send targeted message
        messageBus.send(serverId, "server.shutdown", request)
            .thenAccept(response -> {
                sender.sendMessage("§aShutdown request sent to " + serverId);
            })
            .exceptionally(ex -> {
                sender.sendMessage("§cFailed to send shutdown request!");
                return null;
            });
    }
}
```

**Backend Server (Receiver):**
```java
public class ShutdownHandler {
    private final MessageBus messageBus;
    private final String serverId;
    
    public void register() {
        messageBus.subscribe("server.shutdown", envelope -> {
            ShutdownRequest request = codec.deserialize(
                envelope.getType(),
                envelope.getPayload(),
                ShutdownRequest.class
            );
            
            // Only process if targeted to this server
            if (!request.targetServer().equals(serverId)) {
                return;
            }
            
            // Schedule shutdown
            Bukkit.broadcast(Component.text(
                "§c§lServer shutting down in " + request.delaySeconds() + " seconds!"
            ));
            
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Save data
                Bukkit.savePlayers();
                Bukkit.getWorlds().forEach(World::save);
                
                // Kick players
                String kickMsg = "§cServer is shutting down: " + request.reason();
                Bukkit.getOnlinePlayers().forEach(p -> 
                    p.kick(Component.text(kickMsg))
                );
                
                // Shutdown
                Bukkit.shutdown();
            }, request.delaySeconds() * 20L);
            
            // Send response
            ShutdownResponse response = new ShutdownResponse(
                serverId,
                true,
                "Shutdown scheduled"
            );
            
            messageBus.send(envelope.getSender(), "server.shutdown.response", response);
        });
    }
}
```

### Example 3: Ban Player (Request-Response Pattern)

**Message Definitions:**
```java
public record BanRequest(
    UUID playerId,
    String playerName,
    String reason,
    long duration,
    String issuer
) {}

public record BanResponse(
    UUID playerId,
    boolean success,
    List<String> serversUpdated,
    String error
) {}
```

**Proxy (Initiator):**
```java
public class BanCommand {
    private final MessageBus messageBus;
    
    public void execute(CommandSender sender, String playerName, String reason) {
        UUID playerId = getPlayerUUID(playerName);
        
        BanRequest request = new BanRequest(
            playerId,
            playerName,
            reason,
            -1, // Permanent
            sender.getName()
        );
        
        // Send to all backend servers
        List<CompletableFuture<BanResponse>> futures = new ArrayList<>();
        
        for (String serverId : getBackendServers()) {
            CompletableFuture<Object> future = messageBus.request(
                serverId,
                "player.ban",
                request,
                Duration.ofSeconds(5)
            );
            
            futures.add(future.thenApply(obj -> (BanResponse) obj));
        }
        
        // Wait for all responses
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                List<String> updated = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                
                for (CompletableFuture<BanResponse> future : futures) {
                    try {
                        BanResponse response = future.get();
                        if (response.success()) {
                            updated.addAll(response.serversUpdated());
                        } else {
                            failed.add(response.error());
                        }
                    } catch (Exception e) {
                        failed.add(e.getMessage());
                    }
                }
                
                sender.sendMessage("§aBan applied on " + updated.size() + " servers");
                if (!failed.isEmpty()) {
                    sender.sendMessage("§cFailed on " + failed.size() + " servers");
                }
            });
    }
}
```

**Backend Servers (Responders):**
```java
public class BanHandler {
    private final MessageBus messageBus;
    private final BanManager banManager;
    
    public void register() {
        messageBus.subscribe("player.ban", envelope -> {
            BanRequest request = codec.deserialize(
                envelope.getType(),
                envelope.getPayload(),
                BanRequest.class
            );
            
            try {
                // Apply ban locally
                banManager.banPlayer(
                    request.playerId(),
                    request.playerName(),
                    request.reason(),
                    request.duration(),
                    request.issuer()
                );
                
                // Kick if online
                Player player = Bukkit.getPlayer(request.playerId());
                if (player != null) {
                    player.kick(Component.text("§cYou have been banned: " + request.reason()));
                }
                
                // Send success response
                BanResponse response = new BanResponse(
                    request.playerId(),
                    true,
                    List.of(Bukkit.getServer().getName()),
                    null
                );
                
                messageBus.send(envelope.getSender(), envelope.getCorrelationId(), response);
                
            } catch (Exception e) {
                // Send error response
                BanResponse response = new BanResponse(
                    request.playerId(),
                    false,
                    List.of(),
                    e.getMessage()
                );
                
                messageBus.send(envelope.getSender(), envelope.getCorrelationId(), response);
            }
        });
    }
}
```

## Finding Players Across Network

**Proxy-Side Implementation:**
```java
public record PlayerSearchRequest(UUID playerId) {}
public record PlayerSearchResponse(
    UUID playerId,
    String serverName,
    String proxyName,
    boolean found
) {}

public class PlayerLocator {
    private final MessageBus messageBus;
    
    public CompletableFuture<PlayerLocation> findPlayer(UUID playerId) {
        PlayerSearchRequest request = new PlayerSearchRequest(playerId);
        
        // Broadcast to all proxies
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        
        for (String proxyId : getProxyServers()) {
            futures.add(
                messageBus.request(proxyId, "player.search", request, Duration.ofSeconds(2))
            );
        }
        
        // Return first successful response
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(obj -> {
                PlayerSearchResponse response = (PlayerSearchResponse) obj;
                if (response.found()) {
                    return new PlayerLocation(
                        response.playerId(),
                        response.serverName(),
                        response.proxyName()
                    );
                }
                return null;
            });
    }
}

// Each proxy handles search locally
public class ProxySearchHandler {
    public void register() {
        messageBus.subscribe("player.search", envelope -> {
            PlayerSearchRequest request = codec.deserialize(
                envelope.getType(),
                envelope.getPayload(),
                PlayerSearchRequest.class
            );
            
            ProxiedPlayer player = ProxyServer.getInstance().getPlayer(request.playerId());
            
            PlayerSearchResponse response;
            if (player != null && player.getServer() != null) {
                response = new PlayerSearchResponse(
                    request.playerId(),
                    player.getServer().getInfo().getName(),
                    ProxyServer.getInstance().getName(),
                    true
                );
            } else {
                response = new PlayerSearchResponse(
                    request.playerId(),
                    null,
                    null,
                    false
                );
            }
            
            messageBus.send(envelope.getSender(), envelope.getCorrelationId(), response);
        });
    }
}
```

## Core Components

### MessageBus Interface
```java
public interface MessageBus {
    // Broadcast to all servers
    CompletableFuture<Void> broadcast(String type, Object payload);
    
    // Send to specific server
    CompletableFuture<Void> send(String target, String type, Object payload);
    
    // Request with response
    CompletableFuture<Object> request(String target, String type, Object payload, Duration timeout);
    
    // Subscribe to messages
    void subscribe(String type, MessageHandler handler);
}
```

### MessageEnvelope
```java
public record MessageEnvelope(
    String type,
    String sender,
    String target,
    String correlationId,
    Object payload,
    long timestamp,
    int version
) {}
```

## Best Practices

1. **Always use typed messages** - Create record classes for each message type
2. **Handle failures gracefully** - Use timeouts and exception handling
3. **Version your messages** - Include version field for compatibility
4. **Use correlation IDs** - For request-response tracking
5. **Validate message targets** - Check server exists before sending
6. **Implement retries** - For critical operations
7. **Log message activity** - For debugging distributed systems

## Implementation Notes

This module provides interfaces only. Runtime modules provide implementations:
- **Redis Pub/Sub** - Production use with persistence
- **In-Memory** - Development and testing
- **RabbitMQ** - Optional enterprise messaging

## Dependencies

- Jackson for JSON serialization
- Java 17+ for records
- Lettuce (Redis implementation)
- Optional: RabbitMQ client