# Message Bus API

Core messaging interfaces for server-to-server communication in the Fulcrum framework.

## Overview

The Message Bus API provides a simple yet powerful abstraction for inter-server messaging, supporting:
- Broadcast messages to all servers
- Targeted messages to specific servers
- Request-response patterns with timeouts
- Type-safe message handling with codec registry

## Core Components

### MessageBus
The main interface for sending and receiving messages:
- `broadcast()` - Send a message to all servers
- `send()` - Send a message to a specific server
- `request()` - Send a request and await a response
- `subscribe()` / `unsubscribe()` - Manage message handlers

### MessageEnvelope
Container for message metadata and payload:
- Message type and routing information
- Sender and target server IDs
- Correlation ID for request-response tracking
- Timestamp and version for compatibility

### CodecRegistry
Manages message serialization/deserialization:
- Register message types with their classes
- Automatic JSON conversion using Jackson
- Type-safe deserialization with validation

### PlayerLocator
Helper for finding players across the network:
- Locate players by UUID
- Returns proxy and server information
- Async operation with CompletableFuture

## Usage Example

```java
// Register message types
CodecRegistry codec = new CodecRegistry();
codec.register("player.transfer", PlayerTransferMessage.class);

// Subscribe to messages
messageBus.subscribe("player.transfer", envelope -> {
    PlayerTransferMessage msg = codec.deserialize(
        envelope.getType(), 
        envelope.getPayload(), 
        PlayerTransferMessage.class
    );
    // Handle the message
});

// Send a targeted message
messageBus.send("lobby-1", "player.transfer", transferData);

// Broadcast to all servers
messageBus.broadcast("maintenance.alert", alertData);

// Request with response
CompletableFuture<Object> response = messageBus.request(
    "game-server-2", 
    "player.stats", 
    playerId, 
    Duration.ofSeconds(5)
);
```

## Implementation Notes

This module provides only the API interfaces. Actual implementations should be provided by runtime modules using appropriate messaging infrastructure (Redis Pub/Sub, RabbitMQ, etc.).

## Dependencies

- Jackson for JSON serialization
- Java 17+ for records and modern APIs