package sh.harold.fulcrum.fundamentals.props.model;

/**
 * Options for placing props within a target world.
 */
public final class PropPlacementOptions {
    private final int verticalOffset;
    private final boolean ignoreAirBlocks;
    private final String spawnPoiKey;

    private PropPlacementOptions(Builder builder) {
        this.verticalOffset = builder.verticalOffset;
        this.ignoreAirBlocks = builder.ignoreAirBlocks;
        this.spawnPoiKey = builder.spawnPoiKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int verticalOffset() {
        return verticalOffset;
    }

    public boolean ignoreAirBlocks() {
        return ignoreAirBlocks;
    }

    public String spawnPoiKey() {
        return spawnPoiKey;
    }

    public static final class Builder {
        private int verticalOffset;
        private boolean ignoreAirBlocks;
        private String spawnPoiKey;

        public Builder verticalOffset(int offset) {
            this.verticalOffset = offset;
            return this;
        }

        public Builder ignoreAirBlocks(boolean ignoreAirBlocks) {
            this.ignoreAirBlocks = ignoreAirBlocks;
            return this;
        }

        public Builder spawnPoiKey(String spawnPoiKey) {
            this.spawnPoiKey = spawnPoiKey;
            return this;
        }

        public PropPlacementOptions build() {
            return new PropPlacementOptions(this);
        }
    }
}
