# Data API

Storage abstraction layer supporting MongoDB, JSON files, and in-memory backends with transactions and complex queries.

## Setup

```java
// JSON backend for development
ConnectionAdapter adapter = new JsonConnectionAdapter(Path.of("./data"));
DataAPI dataAPI = DataAPI.create(adapter);

// MongoDB for production
ConnectionAdapter adapter = new MongoConnectionAdapter(
    "mongodb://localhost:27017",
    "database_name",
    "username",
    "password"
);
DataAPI dataAPI = DataAPI.create(adapter);
```

### Ensuring PostgreSQL Schemas

```java
SchemaRegistry.ensureSchema(
    postgresAdapter,
    SchemaDefinition.fromResource(
        "example-schema-001",
        "Create tables for Example service",
        getClass().getClassLoader(),
        "migrations/example.sql"
    )
);
```

## Basic Operations

```java
// Player documents
Document player = dataAPI.player(playerUuid);
player.set("name", "Steve");
player.set("stats.level", 10);
player.set("stats.experience", 1500);

// Get with defaults
int level = player.get("stats.level", 1);
double balance = player.get("economy.balance", 0.0);

// Collections
Collection guilds = dataAPI.guilds();
Document guild = guilds.select("guild-id");
guild.set("members", memberList);
```

## Querying

```java
// Find high-level players
List<Document> players = dataAPI.players()
    .where("stats.level").greaterThan(20)
    .and("online").equalTo(true)
    .sort("stats.level", false)
    .limit(10)
    .execute();

// Complex conditions
List<Document> results = dataAPI.from("items")
    .where("type").in(Arrays.asList("WEAPON", "ARMOR"))
    .and("rarity").equalTo("LEGENDARY")
    .or("price").lessThan(1000)
    .execute();

// Aggregations
double totalBalance = dataAPI.players()
    .where("online").equalTo(true)
    .sum("economy.balance");

long onlineCount = dataAPI.players()
    .where("online").equalTo(true)
    .count();
```

## Transactions

```java
Transaction tx = dataAPI.transaction();
try {
    tx.begin();
    
    Document sender = tx.from("players").select(senderId);
    Document receiver = tx.from("players").select(receiverId);
    
    double amount = 100.0;
    sender.set("balance", sender.get("balance", 0.0) - amount);
    receiver.set("balance", receiver.get("balance", 0.0) + amount);
    
    tx.commit();
} catch (Exception e) {
    tx.rollback();
}
```

## Query API Reference

### Comparison Operations
- `equalTo(value)` - Exact match
- `notEquals(value)` - Not equal
- `greaterThan(value)` - Greater than
- `lessThan(value)` - Less than
- `in(list)` - Value in list
- `contains(value)` - Contains value (strings/arrays)

### String Operations
- `startsWith(prefix)` - String prefix match
- `endsWith(suffix)` - String suffix match
- `matches(regex)` - Regex pattern match

### Logical Operations
- `and(path)` - AND condition
- `or(path)` - OR condition
- `not()` - Negate next condition
- `orWhere(subQuery)` - Nested OR
- `andWhere(subQuery)` - Nested AND

### Collection Operations
- `isEmpty()` - Check if empty
- `isNotEmpty()` - Check if not empty
- `size(n)` - Collection/string size

### Results
- `execute()` - Get all results
- `first()` - Get first result
- `count()` - Count results
- `limit(n)` - Limit results
- `skip(n)` - Skip results
- `sort(field, asc)` - Sort results

### Aggregations
- `sum(path)` - Sum values
- `avg(path)` - Average values
- `min(path)` - Minimum value
- `max(path)` - Maximum value

## Transaction Isolation Levels

- `READ_UNCOMMITTED` - Can read uncommitted changes
- `READ_COMMITTED` - Only read committed changes
- `REPEATABLE_READ` - Same query returns same results
- `SERIALIZABLE` - Transactions execute serially
