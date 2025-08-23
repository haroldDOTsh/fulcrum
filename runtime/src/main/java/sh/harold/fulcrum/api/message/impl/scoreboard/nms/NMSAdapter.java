package sh.harold.fulcrum.api.message.impl.scoreboard.nms;

import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;

/**
 * Abstract base class for NMS (Net Minecraft Server) adapters.
 * This class provides version-specific implementations for packet-based
 * scoreboard rendering across different Minecraft versions.
 *
 * <p>Each supported Minecraft version should have its own implementation
 * of this adapter that handles the specific packet structures and NMS
 * classes for that version.
 *
 * <p>The adapter pattern is used to isolate version-specific code and
 * provide a clean interface for the scoreboard system.
 */
public abstract class NMSAdapter {

    /**
     * Creates a packet renderer for this NMS version.
     *
     * @return a version-specific packet renderer
     */
    public abstract PacketRenderer createPacketRenderer();

    /**
     * Gets the version information for this adapter.
     *
     * @return the version information string
     */
    public abstract String getVersionInfo();

    /**
     * Checks if this adapter supports the current server version.
     *
     * @return true if this adapter is compatible with the current server
     */
    public abstract boolean isCompatible();

    /**
     * Gets the maximum number of characters allowed per scoreboard line.
     * This varies by Minecraft version.
     *
     * @return the maximum characters per line
     */
    public abstract int getMaxCharactersPerLine();

    /**
     * Gets the maximum number of lines supported by this version.
     *
     * @return the maximum number of lines
     */
    public abstract int getMaxLines();

    /**
     * Checks if this version supports colored text on scoreboards.
     *
     * @return true if colored text is supported
     */
    public abstract boolean supportsColoredText();

    /**
     * Checks if this version supports custom scoreboard titles.
     *
     * @return true if custom titles are supported
     */
    public abstract boolean supportsCustomTitles();

    /**
     * Initializes the adapter with any required setup.
     * This method is called once when the adapter is created.
     *
     * @throws Exception if initialization fails
     */
    public abstract void initialize() throws Exception;

    /**
     * Cleans up any resources used by this adapter.
     * This method is called when the plugin is shutting down.
     */
    public abstract void cleanup();
}