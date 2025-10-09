package sh.harold.fulcrum.api.world.paste;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Result of a paste operation.
 */
public class PasteResult {
    
    private final String operationId;
    private final boolean success;
    private final String errorMessage;
    private final int blocksAffected;
    private final int entitiesCreated;
    private final Instant startTime;
    private final Instant endTime;
    private final Region affectedRegion;
    
    private PasteResult(Builder builder) {
        this.operationId = builder.operationId;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.blocksAffected = builder.blocksAffected;
        this.entitiesCreated = builder.entitiesCreated;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.affectedRegion = builder.affectedRegion;
    }
    
    /**
     * Get the unique operation ID.
     */
    public String getOperationId() {
        return operationId;
    }
    
    /**
     * Check if the paste operation was successful.
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Get the error message if the operation failed.
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }
    
    /**
     * Get the number of blocks affected by the operation.
     */
    public int getBlocksAffected() {
        return blocksAffected;
    }
    
    /**
     * Get the number of entities created.
     */
    public int getEntitiesCreated() {
        return entitiesCreated;
    }
    
    /**
     * Get the start time of the operation.
     */
    public Instant getStartTime() {
        return startTime;
    }
    
    /**
     * Get the end time of the operation.
     */
    public Instant getEndTime() {
        return endTime;
    }
    
    /**
     * Get the duration of the operation.
     */
    public Duration getDuration() {
        if (startTime == null || endTime == null) {
            return Duration.ZERO;
        }
        return Duration.between(startTime, endTime);
    }
    
    /**
     * Get the region affected by the paste operation.
     */
    public Optional<Region> getAffectedRegion() {
        return Optional.ofNullable(affectedRegion);
    }
    
    /**
     * Create a successful result.
     */
    public static PasteResult success(String operationId, int blocksAffected, int entitiesCreated, 
                                     Instant startTime, Instant endTime, Region affectedRegion) {
        return builder()
                .operationId(operationId)
                .success(true)
                .blocksAffected(blocksAffected)
                .entitiesCreated(entitiesCreated)
                .startTime(startTime)
                .endTime(endTime)
                .affectedRegion(affectedRegion)
                .build();
    }
    
    /**
     * Create a failure result.
     */
    public static PasteResult failure(String operationId, String errorMessage, Instant startTime) {
        return builder()
                .operationId(operationId)
                .success(false)
                .errorMessage(errorMessage)
                .startTime(startTime)
                .endTime(Instant.now())
                .build();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String operationId;
        private boolean success;
        private String errorMessage;
        private int blocksAffected;
        private int entitiesCreated;
        private Instant startTime;
        private Instant endTime;
        private Region affectedRegion;
        
        public Builder operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }
        
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }
        
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }
        
        public Builder blocksAffected(int blocksAffected) {
            this.blocksAffected = blocksAffected;
            return this;
        }
        
        public Builder entitiesCreated(int entitiesCreated) {
            this.entitiesCreated = entitiesCreated;
            return this;
        }
        
        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }
        
        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }
        
        public Builder affectedRegion(Region affectedRegion) {
            this.affectedRegion = affectedRegion;
            return this;
        }
        
        public PasteResult build() {
            return new PasteResult(this);
        }
    }
}