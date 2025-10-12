package sh.harold.fulcrum.api.world.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;

/**
 * Command to show detailed world information including metadata.
 * Usage: /world info <name>
 * Permission: fulcrum.world.admin
 */
public class WorldInfoCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("info")
                .requires(source -> source.getSender().hasPermission("fulcrum.world.admin"))
                .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument("world", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    Bukkit.getWorlds().stream()
                                            .map(World::getName)
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    var sender = context.getSource().getSender();
                                    String worldName = context.getArgument("world", String.class);

                                    World world = Bukkit.getWorld(worldName);
                                    if (world == null) {
                                        sender.sendMessage(Component.text("World '" + worldName + "' not found!", NamedTextColor.RED));
                                        return 0;
                                    }

                                    // Header
                                    Component header = Component.text("=== World Info: " + worldName + " ===", NamedTextColor.GOLD, TextDecoration.BOLD);
                                    sender.sendMessage(header);

                                    // Basic Info
                                    sender.sendMessage(Component.text("Environment: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getEnvironment().name(), NamedTextColor.AQUA)));

                                    sender.sendMessage(Component.text("Difficulty: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getDifficulty().name(), NamedTextColor.YELLOW)));

                                    sender.sendMessage(Component.text("Seed: ", NamedTextColor.GRAY)
                                            .append(Component.text(String.valueOf(world.getSeed()), NamedTextColor.GREEN)));

                                    // Spawn Location
                                    var spawn = world.getSpawnLocation();
                                    sender.sendMessage(Component.text("Spawn: ", NamedTextColor.GRAY)
                                            .append(Component.text(String.format("X: %.1f, Y: %.1f, Z: %.1f",
                                                    spawn.getX(), spawn.getY(), spawn.getZ()), NamedTextColor.AQUA)));

                                    // World Border
                                    WorldBorder border = world.getWorldBorder();
                                    sender.sendMessage(Component.text("World Border: ", NamedTextColor.GRAY)
                                            .append(Component.text(String.format("Size: %.0f, Center: (%.1f, %.1f)",
                                                    border.getSize(), border.getCenter().getX(), border.getCenter().getZ()), NamedTextColor.YELLOW)));

                                    // Time and Weather
                                    sender.sendMessage(Component.text("Time: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getTime() + " (" + getTimeString(world.getTime()) + ")", NamedTextColor.AQUA)));

                                    sender.sendMessage(Component.text("Weather: ", NamedTextColor.GRAY)
                                            .append(Component.text(getWeatherString(world), NamedTextColor.AQUA)));

                                    // Performance Metrics
                                    sender.sendMessage(Component.text("--- Performance ---", NamedTextColor.GOLD));
                                    sender.sendMessage(Component.text("Players: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getPlayerCount(), NamedTextColor.GREEN)));

                                    sender.sendMessage(Component.text("Loaded Chunks: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getLoadedChunks().length, NamedTextColor.GREEN)));

                                    sender.sendMessage(Component.text("Entities: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getEntityCount(), NamedTextColor.GREEN)));

                                    sender.sendMessage(Component.text("Tile Entities: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getTileEntityCount(), NamedTextColor.GREEN)));

                                    sender.sendMessage(Component.text("Chunk Ticket Count: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getChunkCount(), NamedTextColor.GREEN)));

                                    // Game Rules
                                    sender.sendMessage(Component.text("--- Game Rules ---", NamedTextColor.GOLD));
                                    sender.sendMessage(Component.text("PVP: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getPVP() ? "Enabled" : "Disabled",
                                                    world.getPVP() ? NamedTextColor.GREEN : NamedTextColor.RED)));

                                    sender.sendMessage(Component.text("Keep Inventory: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY).toString(),
                                                    NamedTextColor.AQUA)));

                                    sender.sendMessage(Component.text("Mob Spawning: ", NamedTextColor.GRAY)
                                            .append(Component.text(world.getGameRuleValue(org.bukkit.GameRule.DO_MOB_SPAWNING).toString(),
                                                    NamedTextColor.AQUA)));

                                    return Command.SINGLE_SUCCESS;
                                })
                );
    }

    private static String getTimeString(long time) {
        // Convert Minecraft time to readable format
        long hours = (time / 1000 + 6) % 24;
        long minutes = (time % 1000) * 60 / 1000;
        return String.format("%02d:%02d", hours, minutes);
    }

    private static String getWeatherString(World world) {
        if (world.hasStorm()) {
            return world.isThundering() ? "Thunderstorm" : "Rain";
        }
        return "Clear";
    }
}