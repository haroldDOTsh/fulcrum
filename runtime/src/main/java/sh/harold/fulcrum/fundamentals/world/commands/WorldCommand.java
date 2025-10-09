package sh.harold.fulcrum.fundamentals.world.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import sh.harold.fulcrum.api.lifecycle.ServerIdentifier;
import sh.harold.fulcrum.api.message.Message;
import sh.harold.fulcrum.api.rank.RankUtils;
import sh.harold.fulcrum.api.messagebus.messages.SlotLifecycleStatus;
import sh.harold.fulcrum.api.world.generator.VoidChunkGenerator;
import sh.harold.fulcrum.fundamentals.slot.SimpleSlotOrchestrator;
import sh.harold.fulcrum.fundamentals.world.WorldService;
import sh.harold.fulcrum.fundamentals.world.WorldManager;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;
import sh.harold.fulcrum.fundamentals.world.schematic.SchematicInspector;
import sh.harold.fulcrum.fundamentals.world.schematic.SchematicInspector.InspectionResult;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * World command suite for viewing metadata, saving selections, and managing the cache.
 */
public class WorldCommand {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_INSTANT;

    private final Plugin plugin;
    private final WorldService worldService;
    private final WorldManager worldManager;
    private final Map<String, DebugSession> debugSessions = new ConcurrentHashMap<>();

    public WorldCommand(Plugin plugin, WorldService worldService, WorldManager worldManager) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.worldManager = worldManager;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        var root = Commands.literal("world")
            .requires(this::canView)
            .executes(this::handleHelp)
            .then(Commands.literal("list").executes(this::handleList))
            .then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(getWorldNameSuggestions())
                    .executes(this::handleInfo)))
            .then(Commands.literal("pois")
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(getWorldNameSuggestions())
                    .executes(this::handlePois)))
            .then(Commands.literal("status").executes(this::handleStatus))
            .then(Commands.literal("refresh")
                .requires(this::canManage)
                .executes(this::handleRefresh))
            .then(Commands.literal("unload")
                .requires(this::canManage)
                .then(Commands.argument("name", StringArgumentType.string())
                    .suggests(getLoadedDebugWorldSuggestions())
                    .executes(this::handleUnload)))
            .then(Commands.literal("save")
                .requires(this::canManage)
                .then(Commands.argument("mapId", StringArgumentType.word())
                    .then(Commands.argument("gameId", StringArgumentType.word())
                        .then(Commands.argument("author", StringArgumentType.word())
                            .executes(ctx -> handleSave(ctx, false))
                            .then(Commands.argument("displayName", StringArgumentType.greedyString())
                                .executes(ctx -> handleSave(ctx, true)))))))
            .then(Commands.literal("debug")
                .requires(this::canManage)
                .then(Commands.literal("load")
                    .then(Commands.argument("world", StringArgumentType.string())
                        .suggests(getWorldNameSuggestions())
                        .executes(ctx -> handleDebugLoad(ctx, false))
                        .then(Commands.argument("slot", StringArgumentType.word())
                            .executes(ctx -> handleDebugLoad(ctx, true)))))
                .then(Commands.literal("clear")
                    .then(Commands.argument("world", StringArgumentType.string())
                        .suggests(getLoadedDebugWorldSuggestions())
                        .executes(this::handleDebugClear))))
            .then(Commands.literal("help").executes(this::handleHelp));

        return root.build();
    }



    private boolean canView(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (RankUtils.isAdmin(sender)) {
            return true;
        }
        if (sender.hasPermission("fulcrum.world.manage") || sender.hasPermission("fulcrum.world.view")) {
            return true;
        }
        return sender.isOp();
    }

    private boolean canManage(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (RankUtils.isAdmin(sender)) {
            return true;
        }
        if (sender.hasPermission("fulcrum.world.manage")) {
            return true;
        }
        return sender.isOp();
    }

    private int handleHelp(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        sender.sendMessage(Component.text("=== World Commands (/world, /worlds) ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(line("/world list", "List all cached worlds"));
        sender.sendMessage(line("/world info <name>", "Show metadata for a world"));
        sender.sendMessage(line("/world pois <name>", "List POIs extracted from the schematic"));
        sender.sendMessage(line("/world status", "Display cache statistics"));
        sender.sendMessage(line("/world refresh", "Reload the local cache from PostgreSQL"));
        sender.sendMessage(line("/world save <mapId> <gameId> <author> [display]", "Persist your WorldEdit selection"));
        sender.sendMessage(Component.text("Alias: /worlds mirrors /world.", NamedTextColor.DARK_GRAY));
        return Command.SINGLE_SUCCESS;
    }

    private Component line(String command, String description) {
        return Component.text(command, NamedTextColor.YELLOW)
            .append(Component.text(" - " + description, NamedTextColor.GRAY));
    }

    private int handleList(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<LoadedWorld> worlds = worldService.getAllWorlds().stream()
            .sorted(Comparator.comparing(LoadedWorld::getDisplayName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());

        if (worlds.isEmpty()) {
            sender.sendMessage(Component.text("No maps cached from PostgreSQL", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text("=== Cached Worlds ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Total: " + worlds.size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.empty());

        for (LoadedWorld world : worlds) {
            sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(world.getDisplayName(), NamedTextColor.AQUA))
                .append(Component.text(" (" + world.getWorldName() + ")", NamedTextColor.GRAY))
                .append(Component.text(" - game=", NamedTextColor.DARK_GRAY))
                .append(Component.text(world.getGameId(), NamedTextColor.YELLOW))
                .append(Component.text(", author=", NamedTextColor.DARK_GRAY))
                .append(Component.text(world.getAuthor(), NamedTextColor.GREEN))
            );
        }
        return Command.SINGLE_SUCCESS;
    }

    private int handleInfo(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String worldName = context.getArgument("name", String.class);
        LoadedWorld world = worldService.getWorldByName(worldName).orElse(null);
        if (world == null) {
            sender.sendMessage(Component.text("World not found: " + worldName, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text("=== World Info: " + world.getDisplayName() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(labelValue("Database ID", world.getId().toString()));
        sender.sendMessage(labelValue("World Name", world.getWorldName()));
        sender.sendMessage(labelValue("Map ID", world.getMapId()));
        sender.sendMessage(labelValue("Game ID", world.getGameId()));
        sender.sendMessage(labelValue("Author", world.getAuthor()));
        if (world.getMetadata().size() > 0) {
            sender.sendMessage(labelValue("Metadata", world.getMetadata().toString()));
        }
        File file = world.getSchematicFile();
        sender.sendMessage(labelValue("Cached File", file != null ? file.getAbsolutePath() : "<missing>"));
        if (world.getUpdatedAt() != null) {
            sender.sendMessage(labelValue("Last Updated", DATE_FORMAT.format(world.getUpdatedAt())));
        }
        sender.sendMessage(labelValue("POIs", String.valueOf(world.getPois().size())));
        return Command.SINGLE_SUCCESS;
    }

    private int handlePois(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String worldName = context.getArgument("name", String.class);
        LoadedWorld world = worldService.getWorldByName(worldName).orElse(null);
        if (world == null) {
            sender.sendMessage(Component.text("World not found: " + worldName, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        List<PoiDefinition> pois = world.getPois();
        if (pois.isEmpty()) {
            sender.sendMessage(Component.text("No POIs defined in schematic for " + world.getDisplayName(), NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text("=== POIs for " + world.getDisplayName() + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Total: " + pois.size(), NamedTextColor.GRAY));
        sender.sendMessage(Component.empty());

        for (PoiDefinition poi : pois) {
            Component identifier = poi.identifier() != null
                ? Component.text(poi.identifier(), NamedTextColor.AQUA)
                : Component.text("(unnamed)", NamedTextColor.DARK_AQUA);
            sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                .append(identifier)
                .append(Component.text(" [" + poi.type() + "]", NamedTextColor.YELLOW))
                .append(Component.text(" @ ", NamedTextColor.GRAY))
                .append(Component.text(String.format(Locale.ROOT, "%d,%d,%d",
                    poi.position().x(),
                    poi.position().y(),
                    poi.position().z()), NamedTextColor.WHITE))
            );
        }
        return Command.SINGLE_SUCCESS;
    }

    private int handleStatus(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<LoadedWorld> worlds = worldService.getAllWorlds();
        int totalPois = worlds.stream().mapToInt(w -> w.getPois().size()).sum();
        sender.sendMessage(Component.text("=== World Cache Status ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(labelValue("Worlds", String.valueOf(worlds.size())));
        sender.sendMessage(labelValue("Total POIs", String.valueOf(totalPois)));
        sender.sendMessage(labelValue("Cache Directory", new File(plugin.getDataFolder(), "world-cache").getAbsolutePath()));
        return Command.SINGLE_SUCCESS;
    }

    private int handleRefresh(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        Message.info("world.refresh.start").send(sender);
        worldService.refreshCache().whenComplete((ignored, throwable) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof RuntimeException && throwable.getCause() != null ? throwable.getCause() : throwable;
                    plugin.getLogger().log(Level.SEVERE, "Failed to refresh world cache", cause);
                    Message.error("world.refresh.failed", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()).send(sender);
                    return;
                }
                int total = worldService.getAllWorlds().size();
                plugin.getLogger().info("World cache refreshed via /world refresh (" + total + " worlds)");
                Message.success("world.refresh.success", total).send(sender);
            })
        );
        return Command.SINGLE_SUCCESS;
    }


    private int handleDebugLoad(CommandContext<CommandSourceStack> context, boolean hasSlotArgument) {
        String worldName = context.getArgument("world", String.class);
        String family = null;
        if (hasSlotArgument) {
            try {
                family = context.getArgument("slot", String.class);
            } catch (IllegalArgumentException ignored) {
                family = null;
            }
        }
        return loadWorld(context.getSource(), worldName, family);
    }

    private int loadWorld(CommandSourceStack source, String requestedWorldName, String requestedFamily) {
        CommandSender sender = source.getSender();
        LoadedWorld loadedWorld = worldService.getWorldByName(requestedWorldName).orElse(null);
        if (loadedWorld == null) {
            sender.sendMessage(Component.text("World not found: " + requestedWorldName, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        String familyToUse = null;
        if (requestedFamily != null && !requestedFamily.isBlank()) {
            familyToUse = requestedFamily.toLowerCase(Locale.ROOT);
        }

        SimpleSlotOrchestrator orchestrator = resolveSlotOrchestrator();
        if (familyToUse == null && orchestrator != null) {
            familyToUse = orchestrator.getPrimaryFamily();
        }

        String debugWorldName = createDebugWorldName(loadedWorld.getWorldName());
        WorldCreator creator = new WorldCreator(debugWorldName);
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generator(new VoidChunkGenerator());
        creator.generateStructures(false);
        World debugWorld = creator.createWorld();
        if (debugWorld == null) {
            sender.sendMessage(Component.text("Failed to create debug world: " + debugWorldName, NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        debugWorld.setAutoSave(false);
        Location pasteLocation = new Location(debugWorld, 0, 64, 0);

        sender.sendMessage(Component.text("Loading cached world '" + loadedWorld.getDisplayName() + "' into '" + debugWorldName + "'...", NamedTextColor.YELLOW));

        final String familyForSlot = familyToUse;
        final SimpleSlotOrchestrator orchestratorRef = orchestrator;
        worldManager.pasteWorld(loadedWorld.getId(), debugWorld, pasteLocation).whenComplete((result, throwable) ->
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (throwable != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to paste debug world", throwable);
                    sender.sendMessage(Component.text("Failed to paste world: " + throwable.getMessage(), NamedTextColor.RED));
                    Bukkit.unloadWorld(debugWorld, false);
                    return;
                }

                if (result == null || !result.isSuccess()) {
                    String message = result != null ? result.getMessage() : "Unknown paste failure";
                    sender.sendMessage(Component.text("Failed to paste world: " + message, NamedTextColor.RED));
                    Bukkit.unloadWorld(debugWorld, false);
                    return;
                }

                debugWorld.setSpawnLocation(pasteLocation);
                showPoiMarkers(debugWorld, pasteLocation, loadedWorld.getPois());

                if (sender instanceof Player player) {
                    player.teleport(pasteLocation.clone().add(0.5, 1.5, 0.5));
                }

                sender.sendMessage(Component.text(
                    "World pasted into " + debugWorldName + " (" + loadedWorld.getPois().size() + " POIs)",
                    NamedTextColor.GREEN));

                Map<String, String> metadata = new HashMap<>();
                metadata.put("mapId", loadedWorld.getMapId());
                metadata.put("worldName", debugWorldName);
                metadata.put("worldDisplayName", loadedWorld.getDisplayName());
                metadata.put("gameId", loadedWorld.getGameId());
                metadata.put("author", loadedWorld.getAuthor());
                metadata.put("poiCount", String.valueOf(loadedWorld.getPois().size()));

                String slotId = null;
                if (orchestratorRef != null && familyForSlot != null) {
                    slotId = orchestratorRef.registerDebugSlot(
                        familyForSlot,
                        loadedWorld.getGameId(),
                        SlotLifecycleStatus.IN_GAME,
                        0,
                        metadata
                    );

                    if (slotId != null) {
                        sender.sendMessage(Component.text(
                            "Registered debug slot " + slotId + " for family " + familyForSlot,
                            NamedTextColor.AQUA));
                    } else {
                        sender.sendMessage(Component.text(
                            "Failed to register debug slot for family " + familyForSlot,
                            NamedTextColor.RED));
                    }
                } else {
                    sender.sendMessage(Component.text(
                        "No slot orchestrator available; registry not updated.",
                        NamedTextColor.GRAY));
                }

                debugSessions.put(debugWorldName.toLowerCase(Locale.ROOT),
                    new DebugSession(debugWorldName, slotId, familyForSlot));
            })
        );

        return Command.SINGLE_SUCCESS;
    }

    private int handleUnload(CommandContext<CommandSourceStack> context) {
        String worldName = context.getArgument("name", String.class);
        return unloadWorld(context.getSource(), worldName);
    }

    private int handleDebugClear(CommandContext<CommandSourceStack> context) {
        String worldName = context.getArgument("world", String.class);
        return unloadWorld(context.getSource(), worldName);
    }

    private int unloadWorld(CommandSourceStack source, String worldName) {
        CommandSender sender = source.getSender();
        String key = worldName.toLowerCase(Locale.ROOT);
        DebugSession session = debugSessions.get(key);

        World world = Bukkit.getWorld(worldName);
        if (world == null && session != null) {
            world = Bukkit.getWorld(session.worldName());
        }

        if (world == null) {
            sender.sendMessage(Component.text("World not loaded: " + worldName, NamedTextColor.RED));
            debugSessions.remove(key);
            return Command.SINGLE_SUCCESS;
        }

        Location fallback = !Bukkit.getWorlds().isEmpty() ? Bukkit.getWorlds().get(0).getSpawnLocation() : null;
        for (Player player : List.copyOf(world.getPlayers())) {
            if (fallback != null) {
                player.teleport(fallback);
            }
        }

        String canonicalName = world.getName();
        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (unloaded) {
            deleteWorldFolder(new File(Bukkit.getWorldContainer(), canonicalName));
        }

        SimpleSlotOrchestrator orchestrator = resolveSlotOrchestrator();
        if (session != null && orchestrator != null && session.slotId() != null) {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("debug", "true");
            metadata.put("removed", "true");
            metadata.put("worldName", canonicalName);
            if (session.family() != null) {
                metadata.put("family", session.family());
            }

            boolean removed = orchestrator.removeSlot(session.slotId(), SlotLifecycleStatus.COOLDOWN, metadata);
            if (removed) {
                sender.sendMessage(Component.text("Removed debug slot " + session.slotId(), NamedTextColor.AQUA));
            } else {
                sender.sendMessage(Component.text("Failed to remove debug slot " + session.slotId(), NamedTextColor.RED));
            }
        }

        if (session != null) {
            debugSessions.remove(key);
        }

        if (unloaded) {
            sender.sendMessage(Component.text("Unloaded world " + canonicalName, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("World " + canonicalName + " was not unloaded (see console).", NamedTextColor.YELLOW));
        }

        return Command.SINGLE_SUCCESS;
    }

    private int handleSave(CommandContext<CommandSourceStack> context, boolean hasDisplayName) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            Message.error("world.save.players_only").send(sender);
            return Command.SINGLE_SUCCESS;
        }

        String mapId = context.getArgument("mapId", String.class);
        String worldName = canonicalWorldName(mapId);
        String gameId = context.getArgument("gameId", String.class);
        String author = context.getArgument("author", String.class);
        String displayName = null;
        if (hasDisplayName) {
            try {
                displayName = context.getArgument("displayName", String.class);
            } catch (IllegalArgumentException ignored) {
                displayName = null;
            }
        }

        LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
        if (session == null) {
            Message.error("world.save.no_selection").send(player);
            return Command.SINGLE_SUCCESS;
        }

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(player.getWorld());
        Region region;
        try {
            region = session.getSelection(weWorld);
        } catch (IncompleteRegionException exception) {
            Message.error("world.save.incomplete_selection").send(player);
            return Command.SINGLE_SUCCESS;
        }

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        BlockVector3 origin = region.getMinimumPoint();
        clipboard.setOrigin(origin);

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(editSession, region, origin, clipboard, origin);
            copy.setCopyingBiomes(true);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        } catch (WorldEditException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to copy selection for world save", exception);
            Message.error("world.save.copy_failed").send(player);
            return Command.SINGLE_SUCCESS;
        }

        byte[] rawBytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (ClipboardWriter writer = SchematicInspector.defaultFormat().getWriter(output)) {
                writer.write(clipboard);
            }
            rawBytes = output.toByteArray();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize clipboard for world save", exception);
            Message.error("world.save.serialize_failed").send(player);
            return Command.SINGLE_SUCCESS;
        }

        InspectionResult inspectionResult;
        try {
            inspectionResult = new SchematicInspector(plugin.getLogger()).inspect(rawBytes, worldName);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to inspect schematic before save", exception);
            Message.error("world.save.inspect_failed").send(player);
            return Command.SINGLE_SUCCESS;
        }

        if (!inspectionResult.originDetected()) {
            Message.error("world.save.missing_origin").send(player);
            return Command.SINGLE_SUCCESS;
        }

        byte[] sanitizedBytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            try (ClipboardWriter writer = SchematicInspector.defaultFormat().getWriter(output)) {
                writer.write(inspectionResult.clipboard());
            }
            sanitizedBytes = output.toByteArray();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to sanitize schematic before save", exception);
            Message.error("world.save.serialize_failed").send(player);
            return Command.SINGLE_SUCCESS;
        }

        String safeDisplayName = (displayName != null && !displayName.isBlank()) ? displayName : mapId;
        JsonObject metadata = new JsonObject();
        metadata.addProperty("mapId", mapId);
        metadata.addProperty("gameId", gameId);
        metadata.addProperty("author", author);

        String serverId = resolveServerId();
        int width = region.getWidth();
        int height = region.getHeight();
        int length = region.getLength();
        List<PoiDefinition> pois = List.copyOf(inspectionResult.pois());
        addPoisToMetadata(metadata, pois);
        String poiSummary = pois.isEmpty()
            ? "none"
            : pois.stream().map(this::formatPoiSummary).collect(Collectors.joining(", "));

        Message.info("world.save.start", worldName).send(player);
        worldService.saveWorldDefinition(serverId, worldName, safeDisplayName, metadata, sanitizedBytes)
            .whenComplete((result, throwable) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        Throwable cause = throwable instanceof RuntimeException && throwable.getCause() != null ? throwable.getCause() : throwable;
                        plugin.getLogger().log(Level.SEVERE, "Failed to persist world map " + worldName, cause);
                        Message.error("world.save.failed", worldName, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()).send(player);
                        return;
                    }
                    Message.success("world.save.success", worldName, mapId).send(player);
                    Message.info("world.save.size", width, height, length).send(player);
                    Message.info("world.save.pois", pois.size(), poiSummary).send(player);
                    Message.info("world.save.metadata", gameId, author, safeDisplayName).send(player);
                    plugin.getLogger().info(String.format(Locale.ROOT,
                        "Saved world map %s (mapId=%s, server=%s, pois=%d)",
                        worldName, mapId, serverId, pois.size()));
                })
            );
        return Command.SINGLE_SUCCESS;
    }

    private Component labelValue(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
            .append(Component.text(value, NamedTextColor.WHITE));
    }

    private SuggestionProvider<CommandSourceStack> getWorldNameSuggestions() {
        return (context, builder) -> {
            String input = builder.getRemaining().toLowerCase(Locale.ROOT);
            worldService.getAllWorlds().stream()
                .map(LoadedWorld::getWorldName)
                .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(input))
                .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> getLoadedDebugWorldSuggestions() {
        return (context, builder) -> {
            String input = builder.getRemaining().toLowerCase(Locale.ROOT);
            debugSessions.values().stream()
                .map(DebugSession::worldName)
                .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(input))
                .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private String resolveServerId() {
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return plugin.getName();
        }
        Optional<ServerIdentifier> identifier = locator.findService(ServerIdentifier.class);
        return identifier.map(ServerIdentifier::getServerId).orElse(plugin.getName());
    }

    private String formatPoiSummary(PoiDefinition poi) {
        String identifier = poi.identifier();
        if (identifier != null && !identifier.isBlank()) {
            return poi.type() + ":" + identifier;
        }
        return poi.type();
    }

    private SimpleSlotOrchestrator resolveSlotOrchestrator() {
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator == null) {
            return null;
        }
        return locator.findService(SimpleSlotOrchestrator.class).orElse(null);
    }

    private String canonicalWorldName(String mapId) {
        if (mapId == null) {
            throw new IllegalArgumentException("mapId cannot be null");
        }
        String trimmed = mapId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("mapId cannot be blank");
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (normalized.length() > 40) {
            normalized = normalized.substring(0, 40);
        }
        return normalized;
    }

    private String createDebugWorldName(String sourceWorldName) {
        String base = (sourceWorldName != null && !sourceWorldName.isBlank())
            ? sourceWorldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
            : "world";
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        return "debug_" + base + '_' + System.currentTimeMillis();
    }

    private void addPoisToMetadata(JsonObject metadata, List<PoiDefinition> pois) {
        if (metadata == null) {
            return;
        }

        metadata.remove("pois");
        if (pois == null || pois.isEmpty()) {
            return;
        }

        JsonArray array = new JsonArray();
        for (PoiDefinition poi : pois) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", poi.type());
            if (poi.identifier() != null && !poi.identifier().isBlank()) {
                entry.addProperty("id", poi.identifier());
            }
            BlockVector3 position = poi.position();
            entry.addProperty("x", position.x());
            entry.addProperty("y", position.y());
            entry.addProperty("z", position.z());
            JsonObject extra = poi.metadata();
            if (extra != null && !extra.entrySet().isEmpty()) {
                entry.add("metadata", extra.deepCopy());
            }
            array.add(entry);
        }

        if (!array.isEmpty()) {
            metadata.add("pois", array);
        }
    }

    private void showPoiMarkers(World world, Location origin, List<PoiDefinition> pois) {
        if (world == null || origin == null || pois == null || pois.isEmpty()) {
            return;
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (Bukkit.getWorld(world.getUID()) == null) {
                    cancel();
                    return;
                }

                for (PoiDefinition poi : pois) {
                    Location location = origin.clone().add(
                        poi.position().x() + 0.5,
                        poi.position().y() + 0.5,
                        poi.position().z() + 0.5
                    );
                    if (location.getWorld() != null) {
                        location.getWorld().spawnParticle(Particle.END_ROD, location, 12, 0.2, 0.4, 0.2, 0.01);
                        location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location, 2, 0.1, 0.2, 0.1, 0.0);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void deleteWorldFolder(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteWorldFolder(child);
                }
            }
        }
        if (!target.delete()) {
            plugin.getLogger().fine("Could not delete " + target.getAbsolutePath());
        }
    }

    private record DebugSession(String worldName, String slotId, String family) {}
}
