package sh.harold.fulcrum.fundamentals.world;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import sh.harold.fulcrum.api.world.WorldManager;
import sh.harold.fulcrum.api.world.paste.PasteOptions;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Command for world management operations.
 */
public class WorldCommand implements BasicCommand {

    private final Plugin plugin;
    private final WorldManager worldManager;

    public WorldCommand(Plugin plugin, WorldManager worldManager) {
        this.plugin = plugin;
        this.worldManager = worldManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(stack);
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "paste" -> handlePaste(stack, args);
            case "reset" -> handleReset(stack, args);
            case "template" -> handleTemplate(stack, args);
            case "create" -> handleCreate(stack, args);
            case "help" -> sendHelp(stack);
            default -> stack.getSender().sendMessage(
                    Component.text("Unknown subcommand: " + subCommand, NamedTextColor.RED)
            );
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("paste", "reset", "template", "create", "help")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            return switch (subCommand) {
                case "paste", "reset" -> Bukkit.getWorlds().stream()
                        .map(World::getName)
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
                case "template" -> Stream.of("register", "unregister", "list")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
                default -> List.of();
            };
        }

        return List.of();
    }

    @Override
    public @NotNull String permission() {
        return "fulcrum.world.manage";
    }

    private void handlePaste(CommandSourceStack stack, String[] args) {
        if (!(stack.getSender() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be used by players", NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            stack.getSender().sendMessage(Component.text("Usage: /world paste <schematic-file>", NamedTextColor.RED));
            return;
        }

        String schematicName = args[1];
        File schematicFile = new File(worldManager.getSchematicsDirectory(), schematicName);

        if (!schematicFile.exists()) {
            // Try with .schem extension
            schematicFile = new File(worldManager.getSchematicsDirectory(), schematicName + ".schem");
            if (!schematicFile.exists()) {
                // Try with .schematic extension
                schematicFile = new File(worldManager.getSchematicsDirectory(), schematicName + ".schematic");
                if (!schematicFile.exists()) {
                    stack.getSender().sendMessage(Component.text("Schematic file not found: " + schematicName, NamedTextColor.RED));
                    return;
                }
            }
        }

        Location location = player.getLocation();

        stack.getSender().sendMessage(Component.text("Pasting schematic...", NamedTextColor.YELLOW));

        PasteOptions options = PasteOptions.builder()
                .ignoreAirBlocks(false)
                .copyEntities(true)
                .build();

        worldManager.getWorldPaster().pasteSchematic(schematicFile, location, options)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        stack.getSender().sendMessage(Component.text(
                                "Successfully pasted schematic! Blocks affected: " + result.getBlocksAffected(),
                                NamedTextColor.GREEN
                        ));
                    } else {
                        stack.getSender().sendMessage(Component.text(
                                "Failed to paste schematic: " + result.getErrorMessage().orElse("Unknown error"),
                                NamedTextColor.RED
                        ));
                    }
                });
    }

    private void handleReset(CommandSourceStack stack, String[] args) {
        if (args.length < 2) {
            stack.getSender().sendMessage(Component.text("Usage: /world reset <world-name> [template]", NamedTextColor.RED));
            return;
        }

        String worldName = args[1];
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            stack.getSender().sendMessage(Component.text("World not found: " + worldName, NamedTextColor.RED));
            return;
        }

        if (args.length >= 3) {
            String templateName = args[2];
            File template = worldManager.getTemplate(templateName);

            if (template == null) {
                stack.getSender().sendMessage(Component.text("Template not found: " + templateName, NamedTextColor.RED));
                return;
            }

            stack.getSender().sendMessage(Component.text("Resetting world with template...", NamedTextColor.YELLOW));
            worldManager.resetWorld(world, template).thenAccept(success -> {
                if (success) {
                    stack.getSender().sendMessage(Component.text("World reset successfully!", NamedTextColor.GREEN));
                } else {
                    stack.getSender().sendMessage(Component.text("Failed to reset world", NamedTextColor.RED));
                }
            });
        } else {
            stack.getSender().sendMessage(Component.text("Resetting world...", NamedTextColor.YELLOW));
            worldManager.resetWorld(world).thenAccept(success -> {
                if (success) {
                    stack.getSender().sendMessage(Component.text("World reset successfully!", NamedTextColor.GREEN));
                } else {
                    stack.getSender().sendMessage(Component.text("Failed to reset world", NamedTextColor.RED));
                }
            });
        }
    }

    private void handleTemplate(CommandSourceStack stack, String[] args) {
        if (args.length < 2) {
            stack.getSender().sendMessage(Component.text("Usage: /world template <register|unregister|list>", NamedTextColor.RED));
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "register" -> {
                if (args.length < 4) {
                    stack.getSender().sendMessage(Component.text("Usage: /world template register <name> <file>", NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                String fileName = args[3];
                File file = new File(worldManager.getSchematicsDirectory(), fileName);

                if (worldManager.registerTemplate(name, file)) {
                    stack.getSender().sendMessage(Component.text("Template registered: " + name, NamedTextColor.GREEN));
                } else {
                    stack.getSender().sendMessage(Component.text("Failed to register template", NamedTextColor.RED));
                }
            }
            case "unregister" -> {
                if (args.length < 3) {
                    stack.getSender().sendMessage(Component.text("Usage: /world template unregister <name>", NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                if (worldManager.unregisterTemplate(name)) {
                    stack.getSender().sendMessage(Component.text("Template unregistered: " + name, NamedTextColor.GREEN));
                } else {
                    stack.getSender().sendMessage(Component.text("Template not found: " + name, NamedTextColor.RED));
                }
            }
            case "list" -> {
                stack.getSender().sendMessage(Component.text("Registered Templates:", NamedTextColor.GOLD));
                // Would need to add a method to get all templates
                stack.getSender().sendMessage(Component.text("Feature not yet implemented", NamedTextColor.GRAY));
            }
            default ->
                    stack.getSender().sendMessage(Component.text("Unknown template action: " + action, NamedTextColor.RED));
        }
    }

    private void handleCreate(CommandSourceStack stack, String[] args) {
        if (args.length < 3) {
            stack.getSender().sendMessage(Component.text("Usage: /world create <world-name> <template>", NamedTextColor.RED));
            return;
        }

        String worldName = args[1];
        String templateName = args[2];

        File template = worldManager.getTemplate(templateName);
        if (template == null) {
            template = new File(worldManager.getSchematicsDirectory(), templateName);
            if (!template.exists()) {
                stack.getSender().sendMessage(Component.text("Template not found: " + templateName, NamedTextColor.RED));
                return;
            }
        }

        stack.getSender().sendMessage(Component.text("Creating world from template...", NamedTextColor.YELLOW));

        worldManager.createWorldFromTemplate(worldName, template).thenAccept(world -> {
            if (world != null) {
                stack.getSender().sendMessage(Component.text("World created successfully: " + worldName, NamedTextColor.GREEN));
            } else {
                stack.getSender().sendMessage(Component.text("Failed to create world", NamedTextColor.RED));
            }
        });
    }

    private void sendHelp(CommandSourceStack stack) {
        stack.getSender().sendMessage(Component.text("=== World Management Commands ===", NamedTextColor.GOLD));
        stack.getSender().sendMessage(Component.text("/world paste <schematic> - Paste a schematic at your location", NamedTextColor.YELLOW));
        stack.getSender().sendMessage(Component.text("/world reset <world> [template] - Reset a world", NamedTextColor.YELLOW));
        stack.getSender().sendMessage(Component.text("/world template register <name> <file> - Register a template", NamedTextColor.YELLOW));
        stack.getSender().sendMessage(Component.text("/world template unregister <name> - Unregister a template", NamedTextColor.YELLOW));
        stack.getSender().sendMessage(Component.text("/world template list - List all templates", NamedTextColor.YELLOW));
        stack.getSender().sendMessage(Component.text("/world create <name> <template> - Create world from template", NamedTextColor.YELLOW));
    }
}