package sh.harold.fulcrum.api.world.commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
import org.bukkit.Location;
import org.bukkit.World;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.api.world.poi.POIRegistry;

import java.util.Map;

/**
 * Command to list all POIs in a world with their configurations.
 * Usage: {@code /world pois <name>}
 * Permission: fulcrum.world.admin
 */
public class WorldPOIsCommand {

    private final POIRegistry poiRegistry;

    public WorldPOIsCommand(POIRegistry poiRegistry) {
        this.poiRegistry = poiRegistry;
    }

    public LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("pois")
                .requires(source -> RankUtils.isAdmin(source.getSender()))
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

                                    Map<Location, JsonObject> pois = poiRegistry.getWorldPOIs(worldName);

                                    // Header
                                    Component header = Component.text("=== POIs in " + worldName + " ===", NamedTextColor.GOLD, TextDecoration.BOLD);
                                    sender.sendMessage(header);

                                    if (pois.isEmpty()) {
                                        sender.sendMessage(Component.text("No POIs registered in this world.", NamedTextColor.GRAY));
                                        return Command.SINGLE_SUCCESS;
                                    }

                                    var gson = new GsonBuilder().setPrettyPrinting().create();
                                    int index = 1;

                                    for (Map.Entry<Location, JsonObject> entry : pois.entrySet()) {
                                        Location loc = entry.getKey();
                                        JsonObject config = entry.getValue();

                                        // POI Header
                                        Component poiHeader = Component.text(index + ". ", NamedTextColor.YELLOW)
                                                .append(Component.text(config.has("type") ? config.get("type").getAsString() : "Unknown", NamedTextColor.AQUA));
                                        sender.sendMessage(poiHeader);

                                        // Location
                                        Component locInfo = Component.text("   Location: ", NamedTextColor.GRAY)
                                                .append(Component.text(String.format("X: %.1f, Y: %.1f, Z: %.1f",
                                                        loc.getX(), loc.getY(), loc.getZ()), NamedTextColor.GREEN));
                                        sender.sendMessage(locInfo);

                                        // Priority if present
                                        if (config.has("priority")) {
                                            Component priority = Component.text("   Priority: ", NamedTextColor.GRAY)
                                                    .append(Component.text(config.get("priority").getAsInt(), NamedTextColor.YELLOW));
                                            sender.sendMessage(priority);
                                        }

                                        // Configuration (condensed)
                                        Component configHeader = Component.text("   Config: ", NamedTextColor.GRAY);
                                        sender.sendMessage(configHeader);

                                        String configJson = gson.toJson(config);
                                        String[] lines = configJson.split("\n");
                                        for (String line : lines) {
                                            sender.sendMessage(Component.text("     " + line, NamedTextColor.DARK_GRAY));
                                        }

                                        index++;
                                    }

                                    Component footer = Component.text("Total: ", NamedTextColor.GRAY)
                                            .append(Component.text(pois.size() + " POI(s)", NamedTextColor.GOLD));
                                    sender.sendMessage(footer);

                                    return Command.SINGLE_SUCCESS;
                                })
                );
    }
}
