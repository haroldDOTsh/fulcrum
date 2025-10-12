package sh.harold.fulcrum.api.messagebus.adapter;

import java.time.Duration;

/**
 * Configuration for message bus connections.
 * Supports both in-memory and Redis-based implementations.
 * <p>
 * This is a stateless configuration object that provides
 * all necessary settings for establishing message bus connections.
 */
public class MessageBusConnectionConfig {

    private final MessageBusType type;
    private final String host;
    private final int port;
    private final int database;
    private final String password;
    private final Duration connectionTimeout;
    private final Duration retryDelay;
    private final int maxRetries;
    private MessageBusConnectionConfig(Builder builder) {
        this.type = builder.type;
        this.host = builder.host;
        this.port = builder.port;
        this.database = builder.database;
        this.password = builder.password;
        this.connectionTimeout = builder.connectionTimeout;
        this.retryDelay = builder.retryDelay;
        this.maxRetries = builder.maxRetries;
    }

    /**
     * Creates a new builder for message bus configuration.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default in-memory configuration.
     *
     * @return in-memory configuration
     */
    public static MessageBusConnectionConfig inMemory() {
        return new Builder()
                .type(MessageBusType.IN_MEMORY)
                .build();
    }

    /**
     * Creates a default Redis configuration for localhost.
     *
     * @return Redis configuration for localhost
     */
    public static MessageBusConnectionConfig redisDefaults() {
        return new Builder()
                .type(MessageBusType.REDIS)
                .host("localhost")
                .port(6379)
                .build();
    }

    // Getters
    public MessageBusType getType() {
        return type;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getDatabase() {
        return database;
    }

    public String getPassword() {
        return password;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * The type of message bus to use.
     */
    public enum MessageBusType {
        /**
         * In-memory message bus (for single-server setups)
         */
        IN_MEMORY,
        /**
         * Redis-based distributed message bus
         */
        REDIS
    }

    /**
     * Builder for creating message bus configurations.
     */
    public static class Builder {
        private MessageBusType type = MessageBusType.IN_MEMORY;
        private String host = "localhost";
        private int port = 6379;
        private int database = 0;
        private String password;
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private Duration retryDelay = Duration.ofMillis(500);
        private int maxRetries = 3;

        /**
         * Sets the message bus type.
         *
         * @param type the message bus type
         * @return this builder
         */
        public Builder type(MessageBusType type) {
            this.type = type != null ? type : MessageBusType.IN_MEMORY;
            return this;
        }

        /**
         * Sets the Redis host.
         *
         * @param host the Redis host
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host != null ? host : "localhost";
            return this;
        }

        /**
         * Sets the Redis port.
         *
         * @param port the Redis port
         * @return this builder
         */
        public Builder port(int port) {
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }

        /**
         * Sets the Redis database number.
         *
         * @param database the database number
         * @return this builder
         */
        public Builder database(int database) {
            if (database < 0) {
                throw new IllegalArgumentException("Database must be non-negative");
            }
            this.database = database;
            return this;
        }

        /**
         * Sets the Redis password.
         *
         * @param password the password
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Sets the connection timeout.
         *
         * @param connectionTimeout the connection timeout
         * @return this builder
         */
        public Builder connectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout != null ? connectionTimeout : Duration.ofSeconds(5);
            return this;
        }

        /**
         * Sets the retry delay.
         *
         * @param retryDelay the retry delay
         * @return this builder
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay != null ? retryDelay : Duration.ofMillis(500);
            return this;
        }

        /**
         * Sets the maximum number of retries.
         *
         * @param maxRetries the maximum number of retries
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be non-negative");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Builds the message bus configuration.
         *
         * @return the built configuration
         */
        public MessageBusConnectionConfig build() {
            return new MessageBusConnectionConfig(this);
        }
    }
}