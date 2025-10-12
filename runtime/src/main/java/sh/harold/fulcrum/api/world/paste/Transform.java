package sh.harold.fulcrum.api.world.paste;

/**
 * Represents a transformation to apply when pasting.
 */
public class Transform {

    private final int rotationY;
    private final boolean flipX;
    private final boolean flipZ;
    private final double scaleX;
    private final double scaleY;
    private final double scaleZ;

    private Transform(Builder builder) {
        this.rotationY = builder.rotationY;
        this.flipX = builder.flipX;
        this.flipZ = builder.flipZ;
        this.scaleX = builder.scaleX;
        this.scaleY = builder.scaleY;
        this.scaleZ = builder.scaleZ;
    }

    /**
     * Create a new transform builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get an identity transform (no changes).
     */
    public static Transform identity() {
        return new Builder().build();
    }

    /**
     * Create a rotation-only transform.
     */
    public static Transform rotate(int degrees) {
        return new Builder().rotateY(degrees).build();
    }

    /**
     * Get the Y-axis rotation in degrees (0, 90, 180, 270).
     */
    public int getRotationY() {
        return rotationY;
    }

    /**
     * Check if the transformation flips on the X axis.
     */
    public boolean isFlipX() {
        return flipX;
    }

    /**
     * Check if the transformation flips on the Z axis.
     */
    public boolean isFlipZ() {
        return flipZ;
    }

    /**
     * Get the X-axis scale factor.
     */
    public double getScaleX() {
        return scaleX;
    }

    /**
     * Get the Y-axis scale factor.
     */
    public double getScaleY() {
        return scaleY;
    }

    /**
     * Get the Z-axis scale factor.
     */
    public double getScaleZ() {
        return scaleZ;
    }

    public static class Builder {
        private int rotationY = 0;
        private boolean flipX = false;
        private boolean flipZ = false;
        private double scaleX = 1.0;
        private double scaleY = 1.0;
        private double scaleZ = 1.0;

        /**
         * Set Y-axis rotation in degrees.
         * Must be 0, 90, 180, or 270.
         */
        public Builder rotateY(int degrees) {
            if (degrees % 90 != 0 || degrees < 0 || degrees >= 360) {
                throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270 degrees");
            }
            this.rotationY = degrees;
            return this;
        }

        /**
         * Flip on the X axis.
         */
        public Builder flipX(boolean flip) {
            this.flipX = flip;
            return this;
        }

        /**
         * Flip on the Z axis.
         */
        public Builder flipZ(boolean flip) {
            this.flipZ = flip;
            return this;
        }

        /**
         * Set scale factor for all axes.
         */
        public Builder scale(double scale) {
            if (scale <= 0) {
                throw new IllegalArgumentException("Scale must be positive");
            }
            this.scaleX = scale;
            this.scaleY = scale;
            this.scaleZ = scale;
            return this;
        }

        /**
         * Set individual scale factors.
         */
        public Builder scale(double scaleX, double scaleY, double scaleZ) {
            if (scaleX <= 0 || scaleY <= 0 || scaleZ <= 0) {
                throw new IllegalArgumentException("All scale factors must be positive");
            }
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
            return this;
        }

        /**
         * Build the transform.
         */
        public Transform build() {
            return new Transform(this);
        }
    }
}