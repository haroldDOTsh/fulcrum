package sh.harold.fulcrum.api.message.impl.scoreboard.nms.paper_26_1;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import sh.harold.fulcrum.api.message.scoreboard.render.PacketRenderer;
import sh.harold.fulcrum.api.message.scoreboard.render.RenderedScoreboard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PacketRenderer implementation for Paper 26.1.x.
 */
public class PacketRendererPaper26_1 implements PacketRenderer {

    private static final String VERSION = "paper_26_1";
    private static final int MAX_CHARACTERS_PER_LINE = 128;
    private static final int MAX_LINES = 15;
    private static final String OBJECTIVE_NAME = "fulcrum_sb";
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private final ConcurrentMap<UUID, Object> activeObjectives = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Object> activeScoreboards = new ConcurrentHashMap<>();
    private boolean packetBatchingEnabled = true;
    private boolean nmsAvailable = true;

    public PacketRendererPaper26_1() {
        try {
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket");
            Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket");
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
            displayScoreboardBukkit(playerId, scoreboard);
        }
    }

    private void displayScoreboardNMS(UUID playerId, RenderedScoreboard scoreboard) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        try {
            Object serverPlayer = getServerPlayer(player);

            hideScoreboard(playerId);

            Object nmsScoreboard = construct("net.minecraft.world.scores.Scoreboard");
            Object objective = addObjective(nmsScoreboard, scoreboard.getEffectiveTitle());

            sendPacket(serverPlayer, objectivePacket(objective, "METHOD_ADD"));
            sendPacket(serverPlayer, displayObjectivePacket(objective));

            List<String> content = scoreboard.getContent();
            int score = content.size();

            for (String line : content) {
                if (line == null) {
                    Bukkit.getLogger().warning("Scoreboard line is null for player " + playerId + ", skipping...");
                    continue;
                }

                if (line.isEmpty()) {
                    line = "\u00a7r";
                }

                if (line.length() > MAX_CHARACTERS_PER_LINE) {
                    line = line.substring(0, MAX_CHARACTERS_PER_LINE);
                }

                sendPacket(serverPlayer, scorePacket(line, score--));
            }

            activeObjectives.put(playerId, objective);
            activeScoreboards.put(playerId, nmsScoreboard);
        } catch (Exception e) {
            Bukkit.getLogger().severe("Failed to display NMS scoreboard for player " + playerId + ": " + e.getMessage());
            e.printStackTrace();
            displayScoreboardBukkit(playerId, scoreboard);
        }
    }

    private void displayScoreboardBukkit(UUID playerId, RenderedScoreboard scoreboard) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }

        try {
            org.bukkit.scoreboard.Scoreboard bukkitScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            org.bukkit.scoreboard.Objective objective = bukkitScoreboard.registerNewObjective(
                    OBJECTIVE_NAME,
                    org.bukkit.scoreboard.Criteria.DUMMY,
                    legacyComponent(scoreboard.getEffectiveTitle())
            );

            objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);

            List<String> content = scoreboard.getContent();
            int score = content.size();

            for (String line : content) {
                if (line == null) {
                    Bukkit.getLogger().warning("Scoreboard line is null for player " + playerId + ", skipping...");
                    continue;
                }

                if (line.isEmpty()) {
                    line = "\u00a7r";
                }

                if (line.length() > MAX_CHARACTERS_PER_LINE) {
                    line = line.substring(0, MAX_CHARACTERS_PER_LINE);
                }

                org.bukkit.scoreboard.Score lineScore = objective.getScore(line);
                lineScore.setScore(score--);
            }

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

        Object objective = activeObjectives.get(playerId);
        if (objective != null && nmsAvailable) {
            try {
                Object serverPlayer = getServerPlayer(player);

                setObjectiveDisplayName(objective, title != null ? title : "");
                sendPacket(serverPlayer, objectivePacket(objective, "METHOD_CHANGE"));
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to update NMS title for player " + playerId + ": " + e.getMessage());
            }
        } else {
            org.bukkit.scoreboard.Objective bukkitObjective = player.getScoreboard().getObjective(OBJECTIVE_NAME);
            if (bukkitObjective != null) {
                try {
                    bukkitObjective.displayName(legacyComponent(title));
                } catch (Exception e) {
                    Bukkit.getLogger().warning("Failed to update Bukkit title for player " + playerId + ": " + e.getMessage());
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
            activeObjectives.remove(playerId);
            activeScoreboards.remove(playerId);
            return;
        }

        Object objective = activeObjectives.get(playerId);
        if (objective != null && nmsAvailable) {
            try {
                Object serverPlayer = getServerPlayer(player);

                sendPacket(serverPlayer, displayObjectivePacket(null));
                sendPacket(serverPlayer, objectivePacket(objective, "METHOD_REMOVE"));
            } catch (Exception e) {
                Bukkit.getLogger().warning("Failed to hide NMS scoreboard for player " + playerId + ": " + e.getMessage());
            }
        } else {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }

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

        if (scoreboard.getLineCount() > MAX_LINES) {
            return false;
        }

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

    private static Object getServerPlayer(Player player) throws ReflectiveOperationException {
        Method getHandle = player.getClass().getMethod("getHandle");
        return getHandle.invoke(player);
    }

    private static Object addObjective(Object scoreboard, String title) throws ReflectiveOperationException {
        Object criteria = staticField("net.minecraft.world.scores.criteria.ObjectiveCriteria", "DUMMY");
        Object displayName = component(title != null ? title : "");
        Object renderType = staticField("net.minecraft.world.scores.criteria.ObjectiveCriteria$RenderType", "INTEGER");

        for (Method method : scoreboard.getClass().getMethods()) {
            if (!"addObjective".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 6) {
                return method.invoke(scoreboard, OBJECTIVE_NAME, criteria, displayName, renderType, false, null);
            }
            if (params.length == 5) {
                return method.invoke(scoreboard, OBJECTIVE_NAME, criteria, displayName, renderType, false);
            }
            if (params.length == 4) {
                return method.invoke(scoreboard, OBJECTIVE_NAME, criteria, displayName, renderType);
            }
        }

        throw new NoSuchMethodException("Unable to find compatible Scoreboard.addObjective method");
    }

    private static void setObjectiveDisplayName(Object objective, String title) throws ReflectiveOperationException {
        Object displayName = component(title);
        for (Method method : objective.getClass().getMethods()) {
            if ("setDisplayName".equals(method.getName())
                    && method.getParameterCount() == 1
                    && isAssignable(method.getParameterTypes()[0], displayName)) {
                method.invoke(objective, displayName);
                return;
            }
        }
        throw new NoSuchMethodException("Unable to find compatible Objective.setDisplayName method");
    }

    private static Object objectivePacket(Object objective, String methodField) throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket");
        int method = ((Number) staticField(packetClass, methodField)).intValue();
        return construct(packetClass, objective, method);
    }

    private static Object displayObjectivePacket(Object objective) throws ReflectiveOperationException {
        Object sidebar = staticField("net.minecraft.world.scores.DisplaySlot", "SIDEBAR");
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket");
        return construct(packetClass, sidebar, objective);
    }

    private static Object scorePacket(String line, int score) throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket");
        return construct(packetClass, line, OBJECTIVE_NAME, score, Optional.empty(), Optional.empty());
    }

    private static Object component(String text) throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
        Method literal = componentClass.getMethod("literal", String.class);
        return literal.invoke(null, text);
    }

    private static Component legacyComponent(String text) {
        return LEGACY_SECTION.deserialize(text != null ? text : "");
    }

    private static Object staticField(String className, String fieldName) throws ReflectiveOperationException {
        return staticField(Class.forName(className), fieldName);
    }

    private static Object staticField(Class<?> type, String fieldName) throws ReflectiveOperationException {
        Field field = findField(type, fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Object construct(String className, Object... args) throws ReflectiveOperationException {
        return construct(Class.forName(className), args);
    }

    private static Object construct(Class<?> type, Object... args) throws ReflectiveOperationException {
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != args.length) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < params.length; i++) {
                if (!isAssignable(params[i], args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return constructor.newInstance(args);
            }
        }
        throw new NoSuchMethodException("Unable to find compatible constructor for " + type.getName());
    }

    private static void sendPacket(Object serverPlayer, Object packet) throws ReflectiveOperationException {
        Field connectionField = findField(serverPlayer.getClass(), "connection");
        connectionField.setAccessible(true);
        Object connection = connectionField.get(serverPlayer);
        for (Method method : connection.getClass().getMethods()) {
            if ("send".equals(method.getName())
                    && method.getParameterCount() == 1
                    && isAssignable(method.getParameterTypes()[0], packet)) {
                method.invoke(connection, packet);
                return;
            }
        }
        throw new NoSuchMethodException("Unable to find compatible packet send method");
    }

    private static boolean isAssignable(Class<?> parameterType, Object value) {
        if (value == null) {
            return !parameterType.isPrimitive();
        }
        if (!parameterType.isPrimitive()) {
            return parameterType.isAssignableFrom(value.getClass());
        }
        return primitiveWrapper(parameterType).isAssignableFrom(value.getClass());
    }

    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return Void.class;
    }
}
