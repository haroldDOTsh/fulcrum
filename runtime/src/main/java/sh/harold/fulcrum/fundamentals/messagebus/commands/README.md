# Message Debug Command

Debug command for local message simulation without network/Redis.

## Usage

```
/fulcrum messagedebug <subcommand>
/fulcrum msgdebug <subcommand>
```

Permission: `fulcrum.admin.messagedebug`

## Commands

### Simulate Messages

```bash
# Simulate predefined message types
/fulcrum msgdebug simulate heartbeat
/fulcrum msgdebug simulate proxy-announce --capacity 200 --players 50
/fulcrum msgdebug simulate provision-game --gameType bedwars --mode 4v4
```

### Send Raw JSON

```bash
/fulcrum msgdebug raw <type> <json>
/fulcrum msgdebug raw provision-game {"gameType":"bedwars","mode":"4v4","map":"castle"}
```

### List Types

```bash
/fulcrum msgdebug list
# Shows predefined types and active subscriptions
```

## Predefined Types

- `heartbeat` - ServerHeartbeatMessage
- `proxy-announce` - ProxyAnnouncementMessage
- `proxy-discover` - ProxyDiscoveryRequest
- `server-register` - ServerRegistrationRequest
- `provision-game` - Game provisioning placeholder

## How It Works

1. Creates MessageEnvelope with specified type and payload
2. Uses reflection to access internal handler subscriptions
3. Directly triggers registered MessageHandlers
4. Simulates network message reception
5. Reports triggered handler count

## Use Cases

- Test message handlers without Redis
- Debug handler registration
- Rapid development iteration
- Game provisioning testing
- Verify message flows

## Implementation

Compatible with both SimpleMessageBus (dev) and RedisMessageBus (prod) through reflection-based handler access.
