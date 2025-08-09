# Server Lifecycle API

The Server Lifecycle API provides centralized server identification, lifecycle management, and remote control capabilities for the Fulcrum ecosystem.

## Features

- **Unified Server Registry**: Central registration and discovery of all servers
- **Lifecycle Management**: Track server states from STARTING → READY → STOPPING
- **Crash Detection**: Automatic detection and recovery from server crashes
- **Remote Control**: Shutdown and restart servers remotely with countdown warnings
- **Capacity Management**: Soft/hard player caps with intelligent routing
- **Heartbeat System**: Unified heartbeat with TPS, player count, and health metrics
- **Bootstrap Registration**: Early registration before full server initialization

## Core Components

### Data Models

- `ServerMetadata`: Core server information (ID, family, type, status)
- `ServerHeartbeat`: Unified heartbeat containing all metrics
- `ServerRegistration`: Registration request for new servers
- `RegistrationResult`: Result of registration attempt
- `ShutdownRequest`: Request to shutdown a server
- `RestartRequest`: Request to restart a server

### Interfaces

- `ServerRegistry`: Central registry for all servers
- `ServerController`: Remote control operations
- `ServerIdentifier`: Access current server identity
- `ServerLifecycleBootstrap`: Bootstrap registration interface

### Events

- `ServerRegisteredEvent`: Fired when server registers
- `ServerReadyEvent`: Fired when server becomes ready
- `ServerCrashedEvent`: Fired when crash detected
- `ServerShutdownEvent`: Fired when shutdown begins

## Server ID Format

- Static servers: `{family}{number}` (e.g., `mini1`, `mega2`)
- Dynamic servers: `dynamic{number}{letter}` (e.g., `dynamic104D`)
- Reserved for 5 minutes after crash for recovery

## Status Transitions

```
STARTING → READY → STOPPING → OFFLINE
                 ↘ RESTARTING ↗
```

## Capacity Model

- **Soft Cap**: Stop directing new players but allow friends/staff
- **Hard Cap**: Block all non-staff joins
- Health check: TPS ≥ 19.0 for healthy status

## Integration Points

- Redis for distributed state management
- MessageBus for remote control commands
- Compatible with existing PlayerLocator through adapters