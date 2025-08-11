package sh.harold.fulcrum.velocity.config;

public class RedisConfig {
    
    private boolean enabled = false;
    private String host = "localhost";
    private int port = 6379;
    private String password = "";
    private int database = 0;
    private int timeout = 2000;
    
    // Pool configuration
    private int maxTotal = 8;
    private int maxIdle = 8;
    private int minIdle = 0;
    
    public RedisConfig() {
        // Default constructor with default values
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
    
    public int getMaxTotal() {
        return maxTotal;
    }
    
    public void setMaxTotal(int maxTotal) {
        this.maxTotal = maxTotal;
    }
    
    public int getMaxIdle() {
        return maxIdle;
    }
    
    public void setMaxIdle(int maxIdle) {
        this.maxIdle = maxIdle;
    }
    
    public int getMinIdle() {
        return minIdle;
    }
    
    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }
}