package sh.harold.fulcrum.api.message.impl.scoreboard.nms.paper_26_1;

import org.bukkit.Bukkit;
import sh.harold.fulcrum.api.message.impl.scoreboard.nms.NMSAdapter;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;

/**
 * NMS adapter for Paper 26.1.x scoreboard packet rendering.
 */
public class NMSAdapterPaper26_1 extends NMSAdapter {

    private static final String VERSION = "paper_26_1";
    private static final int MAX_CHARACTERS_PER_LINE = 128;
    private static final int MAX_LINES = 15;

    private PacketRendererPaper26_1 packetRenderer;

    @Override
    public PacketRenderer createPacketRenderer() {
        if (packetRenderer == null) {
            packetRenderer = new PacketRendererPaper26_1();
        }
        return packetRenderer;
    }

    @Override
    public String getVersionInfo() {
        return "Paper 26.1.x (" + VERSION + ")";
    }

    @Override
    public boolean isCompatible() {
        try {
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket");
            Class.forName("net.minecraft.world.scores.Scoreboard");
            Class.forName("net.minecraft.world.scores.Objective");
            Class.forName("net.minecraft.network.chat.Component");
            Class.forName("net.minecraft.server.level.ServerPlayer");
            Class.forName("net.minecraft.world.scores.criteria.ObjectiveCriteria");

            return Bukkit.getMinecraftVersion().startsWith("26.1.");
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
        return true;
    }

    @Override
    public boolean supportsCustomTitles() {
        return true;
    }

    @Override
    public void initialize() throws Exception {
        if (!isCompatible()) {
            throw new UnsupportedOperationException("NMS adapter " + VERSION + " is not compatible with this server version");
        }

        packetRenderer = new PacketRendererPaper26_1();

        try {
            Class.forName("net.minecraft.world.scores.criteria.ObjectiveCriteria");
            Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        } catch (ClassNotFoundException e) {
            throw new Exception("Critical NMS classes not found for " + VERSION + ": " + e.getMessage());
        }
    }

    @Override
    public void cleanup() {
        packetRenderer = null;
    }
}
