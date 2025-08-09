package sh.harold.fulcrum.velocity.config;

public class RedisConfig {
    
    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password = "";
    private int database = 0;
    private int timeout = 5000;
    private int poolSize = 10;
    
    public RedisConfig() {
        // Default constructor with default values
    }
    
    public RedisConfig(String host, int port, String password, int database, int timeout, int poolSize) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.timeout = timeout;
        this.poolSize = poolSize;
        this.enabled = true; // If constructed with parameters, assume it's enabled
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public int getDatabase() {
        return database;
    }
    
    public void setDatabase(int database) {
        this.database = database;
    }
    
    public int getTimeout() {
        return timeout;
    }
    
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
    
    public int getPoolSize() {
        return poolSize;
    }
    
    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
    
    public static class Builder {
        private boolean enabled = false;
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private int timeout = 5000;
        private int poolSize = 10;
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder host(String host) {
            this.host = host;
            return this;
        }
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder password(String password) {
            this.password = password;
            return this;
        }
        
        public Builder database(int database) {
            this.database = database;
            return this;
        }
        
        public Builder timeout(int timeout) {
            this.timeout = timeout;
            return this;
        }
        
        public Builder poolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }
        
        public RedisConfig build() {
            RedisConfig config = new RedisConfig(host, port, password, database, timeout, poolSize);
            config.setEnabled(enabled);
            return config;
        }
    }
}