package sh.harold.fulcrum.api.message.scoreboard.nms.v1_21_R1;

import org.bukkit.Bukkit;
import sh.harold.fulcrum.api.message.scoreboard.nms.NMSAdapter;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;

/**
 * NMS adapter for Minecraft 1.21.6/7 (v1_21_R1).
 * This adapter provides version-specific implementations for packet-based scoreboard rendering
 * using proper NMS classes with paper-userdev.
 */
public class NMSAdapterV1_21_R1 extends NMSAdapter {

    private static final String VERSION = "v1_21_R1";
    private static final int MAX_CHARACTERS_PER_LINE = 128; // 1.21 supports longer lines
    private static final int MAX_LINES = 15;

    private PacketRendererV1_21_R1 packetRenderer;

    @Override
    public PacketRenderer createPacketRenderer() {
        if (packetRenderer == null) {
            packetRenderer = new PacketRendererV1_21_R1();
        }
        return packetRenderer;
    }

    @Override
    public String getVersionInfo() {
        return "Minecraft 1.21.6/7 (" + VERSION + ")";
    }

    @Override
    public boolean isCompatible() {
        try {
            // Check if the required NMS classes exist for 1.21.6/7
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket");
            Class.forName("net.minecraft.world.scores.Scoreboard");
            Class.forName("net.minecraft.world.scores.Objective");
            Class.forName("net.minecraft.network.chat.Component");
            Class.forName("net.minecraft.server.level.ServerPlayer");
            Class.forName("net.minecraft.world.scores.criteria.ObjectiveCriteria");

            // Check server version compatibility
            String serverVersion = Bukkit.getServer().getClass().getPackage().getName();
            return serverVersion.contains("v1_21_R1");
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int getMaxCharactersPerLine() {
        return MAX_CHARACTERS_PER_LINE;
    }

    @Override
    public int getMaxLines() {
        return MAX_LINES;
    }

    @Override
    public boolean supportsColoredText() {
        return true; // 1.21 fully supports colored text via Component system
    }

    @Override
    public boolean supportsCustomTitles() {
        return true; // 1.21 supports custom titles via Component system
    }

    @Override
    public void initialize() throws Exception {
        // Verify NMS classes are available
        if (!isCompatible()) {
            throw new UnsupportedOperationException("NMS adapter v1_21_R1 is not compatible with this server version");
        }

        // Initialize packet renderer
        packetRenderer = new PacketRendererV1_21_R1();

        // Verify critical NMS functionality
        try {
            // Test that we can access the required NMS classes
            Class.forName("net.minecraft.world.scores.criteria.ObjectiveCriteria");
            Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        } catch (ClassNotFoundException e) {
            throw new Exception("Critical NMS classes not found for v1_21_R1: " + e.getMessage());
        }
    }

    @Override
    public void cleanup() {
        if (packetRenderer != null) {
            // Clean up any resources
            packetRenderer = null;
        }
    }
}