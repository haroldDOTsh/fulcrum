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

/**
 * Command to list all loaded worlds.
 * Usage: /world list
 * Permission: fulcrum.world.admin
 */
public class WorldListCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("list")
                .requires(source -> source.getSender().hasPermission("fulcrum.world.admin"))
                .executes(context -> {
                    var sender = context.getSource().getSender();

                    Component header = Component.text("=== Loaded Worlds ===", NamedTextColor.GOLD, TextDecoration.BOLD);
                    sender.sendMessage(header);

                    for (World world : Bukkit.getWorlds()) {
                        Component worldInfo = Component.text("• ", NamedTextColor.GRAY)
                                .append(Component.text(world.getName(), NamedTextColor.AQUA))
                                .append(Component.text(" [", NamedTextColor.GRAY))
                                .append(Component.text(world.getEnvironment().name(), NamedTextColor.YELLOW))
                                .append(Component.text("]", NamedTextColor.GRAY))
                                .append(Component.text(" Players: ", NamedTextColor.GRAY))
                                .append(Component.text(world.getPlayerCount(), NamedTextColor.GREEN))
                                .append(Component.text(" | Chunks: ", NamedTextColor.GRAY))
                                .append(Component.text(world.getLoadedChunks().length, NamedTextColor.GREEN));

                        sender.sendMessage(worldInfo);
                    }

                    Component footer = Component.text("Total: ", NamedTextColor.GRAY)
                            .append(Component.text(Bukkit.getWorlds().size() + " worlds", NamedTextColor.GOLD));
                    sender.sendMessage(footer);

                    return Command.SINGLE_SUCCESS;
                });
    }
}