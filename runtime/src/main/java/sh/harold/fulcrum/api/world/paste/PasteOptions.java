package sh.harold.fulcrum.api.world.paste;

/**
 * Options for controlling schematic paste behavior.
 */
public class PasteOptions {
    
    private boolean ignoreAirBlocks = false;
    private boolean copyEntities = true;
    private boolean copyBiomes = false;
    private boolean fastMode = false;
    private int ticksPerOperation = 1;
    private boolean replaceBlocks = true;
    private boolean trackProgress = true;
    
    private PasteOptions() {}
    
    /**
     * Create a new PasteOptions builder.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Get default paste options.
     */
    public static PasteOptions defaults() {
        return new PasteOptions();
    }
    
    public boolean isIgnoreAirBlocks() {
        return ignoreAirBlocks;
    }
    
    public boolean isCopyEntities() {
        return copyEntities;
    }
    
    public boolean isCopyBiomes() {
        return copyBiomes;
    }
    
    public boolean isFastMode() {
        return fastMode;
    }
    
    public int getTicksPerOperation() {
        return ticksPerOperation;
    }
    
    public boolean isReplaceBlocks() {
        return replaceBlocks;
    }
    
    public boolean isTrackProgress() {
        return trackProgress;
    }
    
    public static class Builder {
        private final PasteOptions options = new PasteOptions();
        
        /**
         * Set whether to ignore air blocks when pasting.
         */
        public Builder ignoreAirBlocks(boolean ignore) {
            options.ignoreAirBlocks = ignore;
            return this;
        }
        
        /**
         * Set whether to copy entities from the schematic.
         */
        public Builder copyEntities(boolean copy) {
            options.copyEntities = copy;
            return this;
        }
        
        /**
         * Set whether to copy biome data.
         */
        public Builder copyBiomes(boolean copy) {
            options.copyBiomes = copy;
            return this;
        }
        
        /**
         * Enable fast mode (may cause more lag but completes faster).
         */
        public Builder fastMode(boolean fast) {
            options.fastMode = fast;
            return this;
        }
        
        /**
         * Set how many ticks between paste operations (for spreading load).
         */
        public Builder ticksPerOperation(int ticks) {
            if (ticks < 1) {
                throw new IllegalArgumentException("Ticks per operation must be at least 1");
            }
            options.ticksPerOperation = ticks;
            return this;
        }
        
        /**
         * Set whether to replace existing blocks.
         */
        public Builder replaceBlocks(boolean replace) {
            options.replaceBlocks = replace;
            return this;
        }
        
        /**
         * Set whether to track progress of the operation.
         */
        public Builder trackProgress(boolean track) {
            options.trackProgress = track;
            return this;
        }
        
        /**
         * Build the PasteOptions.
         */
        public PasteOptions build() {
            return options;
        }
    }
}