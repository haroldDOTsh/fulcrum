package sh.harold.fulcrum.api.menu;

/**
 * Represents anchor points for viewport positioning in custom menus.
 * Used to determine where the viewport is positioned relative to the virtual content.
 */
public class AnchorPoint {

    /**
     * Top-left anchor point.
     */
    public static final AnchorPoint TOP_LEFT = new AnchorPoint(Vertical.TOP, Horizontal.LEFT);
    /**
     * Top-center anchor point.
     */
    public static final AnchorPoint TOP_CENTRE = new AnchorPoint(Vertical.TOP, Horizontal.CENTRE);

    // Pre-defined common anchor combinations (simplified to only used combinations)
    /**
     * Bottom-right anchor point.
     */
    public static final AnchorPoint BOTTOM_RIGHT = new AnchorPoint(Vertical.BOTTOM, Horizontal.RIGHT);
    /**
     * Alias for TOP_LEFT.
     */
    public static final AnchorPoint TOP = TOP_LEFT;
    /**
     * Alias for BOTTOM_RIGHT.
     */
    public static final AnchorPoint BOTTOM = BOTTOM_RIGHT;

    // Shorthand aliases for common usage
    private final Vertical vertical;
    private final Horizontal horizontal;

    /**
     * Creates a new anchor point with the specified vertical and horizontal positions.
     *
     * @param vertical   the vertical anchor position
     * @param horizontal the horizontal anchor position
     */
    public AnchorPoint(Vertical vertical, Horizontal horizontal) {
        this.vertical = vertical;
        this.horizontal = horizontal;
    }

    /**
     * Creates an anchor point from the specified positions.
     *
     * @param vertical   the vertical position
     * @param horizontal the horizontal position
     * @return the anchor point
     */
    public static AnchorPoint of(Vertical vertical, Horizontal horizontal) {
        return new AnchorPoint(vertical, horizontal);
    }

    /**
     * Gets the vertical anchor position.
     *
     * @return the vertical position
     */
    public Vertical getVertical() {
        return vertical;
    }

    /**
     * Gets the horizontal anchor position.
     *
     * @return the horizontal position
     */
    public Horizontal getHorizontal() {
        return horizontal;
    }

    /**
     * Calculates the viewport offset for the given dimensions.
     *
     * @param viewportSize the size of the viewport
     * @param contentSize  the size of the content
     * @param isVertical   true for vertical calculation, false for horizontal
     * @return the offset value
     */
    public int calculateOffset(int viewportSize, int contentSize, boolean isVertical) {
        if (viewportSize >= contentSize) {
            return 0; // No offset needed if viewport is larger than content
        }

        Object anchor = isVertical ? vertical : horizontal;

        if (anchor == Vertical.TOP || anchor == Horizontal.LEFT) {
            return 0;
        } else if (anchor == Vertical.BOTTOM || anchor == Horizontal.RIGHT) {
            return contentSize - viewportSize;
        } else { // CENTRE
            return (contentSize - viewportSize) / 2;
        }
    }

    @Override
    public String toString() {
        return "AnchorPoint{" + vertical + "_" + horizontal + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AnchorPoint)) return false;
        AnchorPoint other = (AnchorPoint) obj;
        return vertical == other.vertical && horizontal == other.horizontal;
    }

    @Override
    public int hashCode() {
        return 31 * vertical.hashCode() + horizontal.hashCode();
    }

    /**
     * Vertical anchor positions.
     */
    public enum Vertical {
        /**
         * Anchor the viewport to the top of the content.
         */
        TOP,

        /**
         * Anchor the viewport to the vertical center of the content.
         */
        CENTRE,

        /**
         * Anchor the viewport to the bottom of the content.
         */
        BOTTOM
    }

    /**
     * Horizontal anchor positions.
     */
    public enum Horizontal {
        /**
         * Anchor the viewport to the left of the content.
         */
        LEFT,

        /**
         * Anchor the viewport to the horizontal center of the content.
         */
        CENTRE,

        /**
         * Anchor the viewport to the right of the content.
         */
        RIGHT
    }
}