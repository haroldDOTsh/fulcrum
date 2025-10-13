package sh.harold.fulcrum.api.world.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import sh.harold.fulcrum.api.rank.RankUtils;

/**
 * Command to show world loading status and memory usage.
 * Usage: /world status
 * Permission: fulcrum.world.admin
 */
public class WorldStatusCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("status")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
                .executes(context -> {
                    var sender = context.getSource().getSender();

                    // Header
                    Component header = Component.text("=== World Loading Status ===", NamedTextColor.GOLD, TextDecoration.BOLD);
                    sender.sendMessage(header);

                    // Memory Info
                    Runtime runtime = Runtime.getRuntime();
                    long maxMemory = runtime.maxMemory() / 1024 / 1024;
                    long totalMemory = runtime.totalMemory() / 1024 / 1024;
                    long freeMemory = runtime.freeMemory() / 1024 / 1024;
                    long usedMemory = totalMemory - freeMemory;

                    sender.sendMessage(Component.text("--- Memory Usage ---", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("Used: ", NamedTextColor.GRAY)
                            .append(Component.text(usedMemory + " MB", NamedTextColor.GREEN))
                            .append(Component.text(" / ", NamedTextColor.GRAY))
                            .append(Component.text(maxMemory + " MB", NamedTextColor.AQUA))
                            .append(Component.text(" (" + (usedMemory * 100 / maxMemory) + "%)", NamedTextColor.YELLOW)));

                    // Overall Statistics
                    int totalWorlds = Bukkit.getWorlds().size();
                    int totalPlayers = Bukkit.getOnlinePlayers().size();
                    int totalChunks = 0;
                    int totalEntities = 0;

                    for (World world : Bukkit.getWorlds()) {
                        totalChunks += world.getLoadedChunks().length;
                        totalEntities += world.getEntityCount();
                    }

                    sender.sendMessage(Component.text("--- Overall Statistics ---", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("Loaded Worlds: ", NamedTextColor.GRAY)
                            .append(Component.text(totalWorlds, NamedTextColor.GREEN)));
                    sender.sendMessage(Component.text("Online Players: ", NamedTextColor.GRAY)
                            .append(Component.text(totalPlayers, NamedTextColor.GREEN)));
                    sender.sendMessage(Component.text("Total Loaded Chunks: ", NamedTextColor.GRAY)
                            .append(Component.text(totalChunks, NamedTextColor.GREEN)));
                    sender.sendMessage(Component.text("Total Entities: ", NamedTextColor.GRAY)
                            .append(Component.text(totalEntities, NamedTextColor.GREEN)));

                    // Per-World Status
                    sender.sendMessage(Component.text("--- Per-World Status ---", NamedTextColor.YELLOW));

                    for (World world : Bukkit.getWorlds()) {
                        String status = getWorldStatus(world);
                        NamedTextColor statusColor = getStatusColor(status);

                        Component worldStatus = Component.text("• ", NamedTextColor.GRAY)
                                .append(Component.text(world.getName(), NamedTextColor.AQUA))
                                .append(Component.text(" [", NamedTextColor.GRAY))
                                .append(Component.text(status, statusColor))
                                .append(Component.text("]", NamedTextColor.GRAY))
                                .append(Component.text(" | Chunks: ", NamedTextColor.GRAY))
                                .append(Component.text(world.getLoadedChunks().length, NamedTextColor.GREEN))
                                .append(Component.text(" | Players: ", NamedTextColor.GRAY))
                                .append(Component.text(world.getPlayerCount(), NamedTextColor.GREEN))
                                .append(Component.text(" | Entities: ", NamedTextColor.GRAY))
                                .append(Component.text(world.getEntityCount(), NamedTextColor.GREEN));

                        sender.sendMessage(worldStatus);
                    }

                    // TPS Information
                    double tps = Bukkit.getTPS()[0];
                    NamedTextColor tpsColor = tps >= 19 ? NamedTextColor.GREEN :
                            tps >= 17 ? NamedTextColor.YELLOW : NamedTextColor.RED;

                    sender.sendMessage(Component.text("--- Server Performance ---", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("TPS: ", NamedTextColor.GRAY)
                            .append(Component.text(String.format("%.2f", tps), tpsColor)));

                    return Command.SINGLE_SUCCESS;
                });
    }

    private static String getWorldStatus(World world) {
        // Determine world status based on activity
        if (world.getPlayerCount() > 0) {
            return "ACTIVE";
        } else if (world.getLoadedChunks().length > 100) {
            return "LOADED";
        } else {
            return "IDLE";
        }
    }

    private static NamedTextColor getStatusColor(String status) {
        return switch (status) {
            case "ACTIVE" -> NamedTextColor.GREEN;
            case "LOADED" -> NamedTextColor.YELLOW;
            case "IDLE" -> NamedTextColor.GRAY;
            default -> NamedTextColor.WHITE;
        };
    }
}
