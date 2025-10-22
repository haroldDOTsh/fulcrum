# Fulcrum Service Lifecycle Management System

## Overview

The Service Lifecycle Management System provides a unified, standardized way for all Fulcrum services (backend servers, proxies, and supporting services) to handle:
- Service registration with the central registry
- Automatic heartbeat management
- Graceful shutdown procedures
- Service status tracking
- Metrics reporting

## Core Components

### 1. ServiceIdentity
Represents the unique identity of a service:
- Temporary ID (used during registration)
- Permanent ID (assigned by registry)
- Service type (SERVER, PROXY, REGISTRY)
- Service role (game, lobby, auth, etc.)
- Network address and port
- Instance UUID and start time

### 2. ServiceMetadata
Tracks service metrics and state:
- Player count and capacity (soft/hard caps)
- TPS (Ticks Per Second)
- Service status (STARTING, AVAILABLE, FULL, EVACUATING, etc.)
- Custom properties
- Load percentage calculation
- Heartbeat tracking

### 3. ServiceStatus
Enum defining all possible service states:
- **STARTING**: Service is initializing
- **REGISTERING**: Registering with the registry
- **AVAILABLE**: Accepting connections
- **FULL**: At capacity, not accepting new connections
- **EVACUATING**: Moving players/connections elsewhere
- **STOPPING**: Graceful shutdown in progress
- **STOPPED**: Service has stopped
- **UNRESPONSIVE**: Not sending heartbeats
- **MAINTENANCE**: In maintenance mode

### 4. ServiceLifecycleManager
Manages the complete lifecycle:
- Automatic registration with retry logic
- Heartbeat scheduling (every 2 seconds)
- Message handler setup
- Shutdown notification broadcasting
- Callback management for lifecycle events

### 5. AbstractLifecycleService
Base class for all services to extend:
- Provides lifecycle management out of the box
- Template methods for customization
- Metric update hooks
- Property management

## Usage Example

```java
public class MyGameServer extends AbstractLifecycleService {
    
    public MyGameServer(MessageBus messageBus, String address, int port) {
        super(messageBus, ServiceType.SERVER, "game", address, port, 100);
    }
    
    @Override
    protected CompletableFuture<Void> initialize() {
        // Initialize your service components
        return CompletableFuture.runAsync(() -> {
            // Load configurations
            // Setup game world
            // Initialize plugins
        });
    }
    
    @Override
    protected void onHeartbeat(ServiceMetadata metadata) {
        // Update metrics before each heartbeat
        metadata.setPlayerCount(getOnlinePlayerCount());
        metadata.setTps(getCurrentTPS());
        
        // Update status based on conditions
        if (isMaintenanceMode()) {
            metadata.setStatus(ServiceStatus.MAINTENANCE);
        }
    }
    
    @Override
    protected void onRegistrationSuccess(String permanentId) {
        // Called when registered successfully
        logger.info("Server registered with ID: " + permanentId);
        startGameLoop();
    }
}

// Starting the service
MyGameServer server = new MyGameServer(messageBus, "localhost", 25565);
server.start().thenRun(() -> {
    System.out.println("Server started!");
});

// Stopping gracefully
server.stop().thenRun(() -> {
    System.out.println("Server stopped!");
});
```

## Benefits

1. **Consistency**: All services use the same registration and heartbeat pattern
2. **Reliability**: Built-in retry logic and timeout handling
3. **Simplicity**: Services just extend AbstractLifecycleService
4. **Flexibility**: Override methods to customize behavior
5. **Monitoring**: Automatic heartbeat and status tracking
6. **Graceful Shutdown**: Proper cleanup and notification

## Migration Guide

To migrate existing services to use this system:

1. **Extend AbstractLifecycleService** instead of implementing custom registration
2. **Remove manual registration code** - it's handled automatically
3. **Remove manual heartbeat logic** - use the onHeartbeat callback
4. **Update shutdown procedures** - use the cleanup() method
5. **Use ServiceStatus enum** for status tracking

### Before (Old Pattern)
```java
// Manual registration
ServerRegistrationRequest request = new ServerRegistrationRequest(...);
messageBus.broadcast(CHANNEL, request);

// Manual heartbeat
scheduler.scheduleAtFixedRate(() -> {
    ServerHeartbeatMessage heartbeat = new ServerHeartbeatMessage(...);
    messageBus.broadcast(HEARTBEAT_CHANNEL, heartbeat);
}, 0, 2, TimeUnit.SECONDS);

// Manual shutdown
messageBus.broadcast(REMOVAL_CHANNEL, removalNotification);
```

### After (New Pattern)
```java
public class MyService extends AbstractLifecycleService {
    // Registration, heartbeats, and shutdown handled automatically
    
    @Override
    protected void onHeartbeat(ServiceMetadata metadata) {
        // Just update metrics
        metadata.setPlayerCount(currentPlayers);
    }
}
```

## Thread Safety

- ServiceIdentity: Immutable after construction (except for ID update)
- ServiceMetadata: Thread-safe with concurrent collections
- ServiceLifecycleManager: Thread-safe with atomic operations
- AbstractLifecycleService: Thread-safe lifecycle management

## Configuration

The system uses sensible defaults:
- Registration timeout: 10 seconds
- Registration retries: 5 attempts
- Heartbeat interval: 2 seconds
- Heartbeat timeout: 5 seconds

These can be adjusted by extending ServiceLifecycleManager if needed.

