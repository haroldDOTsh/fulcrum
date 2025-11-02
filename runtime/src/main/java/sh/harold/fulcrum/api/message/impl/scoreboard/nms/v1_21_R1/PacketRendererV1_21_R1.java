package sh.harold.fulcrum.api.message.impl.scoreboard.nms.v1_21_R1;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketRenderer implementation for Minecraft 1.21.6-1.21.10 (v1_21_R1).
 * This class handles packet-based scoreboard rendering using proper NMS classes
 * with paper-userdev for direct packet manipulation.
 */
public class PacketRendererV1_21_R1 implements PacketRenderer {

    private static final String VERSION = "v1_21_R1";
    private static final int MAX_CHARACTERS_PER_LINE = 128; // 1.21 supports longer lines
    private static final int MAX_LINES = 15;

    // Track active scoreboards for each player
    private final ConcurrentHashMap<UUID, Objective> activeObjectives = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Scoreboard> activeScoreboards = new ConcurrentHashMap<>();
    private boolean packetBatchingEnabled = true;
    private boolean nmsAvailable = true;

    public PacketRendererV1_21_R1() {
        // Check if NMS classes are available
        try {
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
            Class.forName("net.minecraft.server.level.ServerPlayer");
            nmsAvailable = true;
        } catch (ClassNotFoundException e) {
            nmsAvailable = false;
            Bukkit.getLogger().warning("NMS classes not available, falling back to Bukkit API for scoreboard rendering");
        }
    }

    @Override
    public void displayScoreboard(UUID playerId, RenderedScoreboard scoreboard) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboard == null) {
            throw new IllegalArgumentException("Scoreboard cannot be null");
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        try {
            if (nmsAvailable) {
                displayScoreboardNMS(playerId, scoreboard);
            } else {
                displayScoreboardBukkit(playerId, scoreboard);
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to display scoreboard via NMS for player " + playerId + ": " + e.getMessage());
            // Fallback to Bukkit API
            displayScoreboardBukkit(playerId, scoreboard);
        }
    }

    private void displayScoreboardNMS(UUID playerId, RenderedScoreboard scoreboard) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        try {
            ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

            // Clear existing scoreboard if any
            hideScoreboard(playerId);

            // Create new scoreboard and objective
            Scoreboard nmsScoreboard = new Scoreboard();
            String objectiveName = computeObjectiveName(playerId, scoreboard);
            Objective objective = nmsScoreboard.addObjective(
                    objectiveName,
                    ObjectiveCriteria.DUMMY,
                    Component.literal(scoreboard.getEffectiveTitle()),
                    ObjectiveCriteria.RenderType.INTEGER,
                    false,
                    null
            );

            // Create and send objective packet
            ClientboundSetObjectivePacket createObjectivePacket =
                    new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD);
            serverPlayer.connection.send(createObjectivePacket);

            // Display the objective on the sidebar
            ClientboundSetDisplayObjectivePacket displayPacket =
                    new ClientboundSetDisplayObjectivePacket(net.minecraft.world.scores.DisplaySlot.SIDEBAR, objective);
            serverPlayer.connection.send(displayPacket);

            // Add content lines
            List<String> content = scoreboard.getContent();
            int score = content.size();


            for (String line : content) {
                if (line == null) {
                    Bukkit.getLogger().warning("Scoreboard line is null for player " + playerId + ", skipping...");
                    continue;
                }

                // Handle empty lines to ensure compatibility with Minecraft's scoreboard requirements
                // Note: Only apply §r to truly empty strings, not whitespace-only strings which are unique separators
                if (line.isEmpty()) {
                    line = "§r";
                }

                if (line.length() > MAX_CHARACTERS_PER_LINE) {
                    line = line.substring(0, MAX_CHARACTERS_PER_LINE);
                }

                // Create score packet - fixed to use Optional.empty() instead of null
                ClientboundSetScorePacket scorePacket =
                        new ClientboundSetScorePacket(line, objectiveName, score--, Optional.empty(), Optional.empty());
                serverPlayer.connection.send(scorePacket);
            }

            // Track active scoreboard
            activeObjectives.put(playerId, objective);
            activeScoreboards.put(playerId, nmsScoreboard);

        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to display NMS scoreboard for player " + playerId + ": " + e.getMessage());
            e.printStackTrace();
            // Fallback to Bukkit API
            displayScoreboardBukkit(playerId, scoreboard);
        }
    }

    private void displayScoreboardBukkit(UUID playerId, RenderedScoreboard scoreboard) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        try {
            // Create new scoreboard
            org.bukkit.scoreboard.Scoreboard bukkitScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            String objectiveName = computeObjectiveName(playerId, scoreboard);
            org.bukkit.scoreboard.Objective objective = bukkitScoreboard.registerNewObjective(
                    objectiveName,
                    "dummy",
                    scoreboard.getEffectiveTitle()
            );

            objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

            // Set content lines
            List<String> content = scoreboard.getContent();
            int score = content.size();

            for (String line : content) {
                // Handle empty lines to ensure compatibility with Minecraft's scoreboard requirements
                // Note: Only apply §r to truly empty strings, not whitespace-only strings which are unique separators
                if (line.isEmpty()) {
                    line = "§r";
                }

                if (line.length() > MAX_CHARACTERS_PER_LINE) {
                    line = line.substring(0, MAX_CHARACTERS_PER_LINE);
                }

                org.bukkit.scoreboard.Score lineScore = objective.getScore(line);
                lineScore.setScore(score--);
            }

            // Set player's scoreboard
            player.setScoreboard(bukkitScoreboard);

        } catch (Exception e) {
            Bukkit.getLogger().warning("Failed to display Bukkit scoreboard for player " + playerId + ": " + e.getMessage());
        }
    }

    @Override
    public void updateScoreboard(UUID playerId, RenderedScoreboard scoreboard) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboard == null) {
            throw new IllegalArgumentException("Scoreboard cannot be null");
        }

        // For this implementation, we'll recreate the scoreboard for efficiency
        displayScoreboard(playerId, scoreboard);
    }

    @Override
    public void updateTitle(UUID playerId, String title) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        Objective objective = activeObjectives.get(playerId);
        if (objective != null && nmsAvailable) {
            try {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

                // Update the objective's display name
                objective.setDisplayName(Component.literal(title != null ? title : ""));

                // Send updated objective packet
                ClientboundSetObjectivePacket updatePacket =
                        new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE);
                serverPlayer.connection.send(updatePacket);

            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to update NMS title for player " + playerId + ": " + e.getMessage());
            }
        } else {
            // Fallback to Bukkit API
            org.bukkit.scoreboard.Scoreboard bukkitScoreboard = player.getScoreboard();
            if (bukkitScoreboard != null) {
                org.bukkit.scoreboard.Objective sidebar = bukkitScoreboard.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
                if (sidebar != null) {
                    try {
                        sidebar.setDisplayName(title != null ? title : "");
                    } catch (Exception e) {
                        Bukkit.getLogger().warning("Failed to update Bukkit title for player " + playerId + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public void hideScoreboard(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            // Still clean up tracking even if player is offline
            activeObjectives.remove(playerId);
            activeScoreboards.remove(playerId);
            return;
        }

        Objective objective = activeObjectives.get(playerId);
        if (objective != null && nmsAvailable) {
            try {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();

                // Remove the objective from display
                ClientboundSetDisplayObjectivePacket hidePacket =
                        new ClientboundSetDisplayObjectivePacket(net.minecraft.world.scores.DisplaySlot.SIDEBAR, null);
                serverPlayer.connection.send(hidePacket);

                // Remove the objective completely
                ClientboundSetObjectivePacket removePacket =
                        new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE);
                serverPlayer.connection.send(removePacket);

            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to hide NMS scoreboard for player " + playerId + ": " + e.getMessage());
            }
        } else {
            // Fallback to Bukkit API
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }

        // Clean up tracking
        activeObjectives.remove(playerId);
        activeScoreboards.remove(playerId);
    }

    @Override
    public boolean hasScoreboardDisplayed(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }

        return activeObjectives.containsKey(playerId);
    }

    @Override
    public void refreshScoreboard(UUID playerId, RenderedScoreboard scoreboard) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }
        if (scoreboard == null) {
            throw new IllegalArgumentException("Scoreboard cannot be null");
        }

        // Hide current scoreboard and display new one
        hideScoreboard(playerId);
        displayScoreboard(playerId, scoreboard);
    }

    @Override
    public void clearPlayerPackets(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("Player ID cannot be null");
        }

        hideScoreboard(playerId);
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
    public String getVersionInfo() {
        return "NMS PacketRenderer for " + VERSION + " (NMS: " + (nmsAvailable ? "Available" : "Fallback") + ")";
    }

    @Override
    public boolean validateScoreboard(RenderedScoreboard scoreboard) {
        if (scoreboard == null) {
            throw new IllegalArgumentException("Scoreboard cannot be null");
        }

        // Check line count
        if (scoreboard.getLineCount() > MAX_LINES) {
            return false;
        }

        // Check each line length
        for (String line : scoreboard.getContent()) {
            if (line != null && line.length() > MAX_CHARACTERS_PER_LINE) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int getActiveDisplayCount() {
        return activeObjectives.size();
    }

    @Override
    public boolean isPacketBatchingEnabled() {
        return packetBatchingEnabled;
    }

    @Override
    public void setPacketBatchingEnabled(boolean enabled) {
        this.packetBatchingEnabled = enabled;
    }

    private String computeObjectiveName(UUID playerId, RenderedScoreboard scoreboard) {
        String scoreboardId = scoreboard.getScoreboardId() != null ? scoreboard.getScoreboardId() : "default";
        String hashPart = Integer.toHexString(scoreboardId.hashCode());
        if (hashPart.length() > 6) {
            hashPart = hashPart.substring(0, 6);
        }
        String randomPart = UUID.randomUUID().toString().replace("-", "");
        randomPart = randomPart.substring(0, Math.min(8, randomPart.length()));
        String name = "fsb" + hashPart + randomPart;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        return name;
    }
}
