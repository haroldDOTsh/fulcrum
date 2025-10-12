package sh.harold.fulcrum.fundamentals.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.fundamentals.world.model.LoadedWorld;
import sh.harold.fulcrum.fundamentals.world.model.PoiDefinition;
import sh.harold.fulcrum.fundamentals.world.schematic.SchematicInspector;
import sh.harold.fulcrum.fundamentals.world.schematic.SchematicInspector.InspectionResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for hydrating world schematics and metadata from PostgreSQL and caching them locally.
 */
public class WorldService {

    private final Plugin plugin;
    private final PostgresConnectionAdapter connectionAdapter;
    private final Logger logger;
    private final File cacheDirectory;
    private final SchematicInspector inspector;
    private final Map<UUID, LoadedWorld> cacheById = new ConcurrentHashMap<>();
    private final Map<String, LoadedWorld> cacheByName = new ConcurrentHashMap<>();
    private final Map<String, LoadedWorld> cacheByMapId = new ConcurrentHashMap<>();

    public WorldService(Plugin plugin, PostgresConnectionAdapter connectionAdapter) {
        this.plugin = plugin;
        this.connectionAdapter = connectionAdapter;
        this.logger = plugin.getLogger();
        this.cacheDirectory = new File(plugin.getDataFolder(), "world-cache");
        this.inspector = new SchematicInspector(logger);
    }

    public CompletableFuture<Void> initialize() {
        if (SchematicInspector.defaultFormat() == null) {
            throw new IllegalStateException("WorldService requires FAWE .schem support");
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            logger.warning("Failed to create world cache directory: " + cacheDirectory.getAbsolutePath());
        }
        return refreshCache();
    }

    public CompletableFuture<Void> refreshCache() {
        return CompletableFuture.runAsync(this::loadWorlds);
    }

    public void shutdown() {
        cacheById.clear();
        cacheByName.clear();
        cacheByMapId.clear();
    }

    public List<LoadedWorld> getAllWorlds() {
        return new ArrayList<>(cacheById.values());
    }

    public Optional<LoadedWorld> getWorldById(UUID id) {
        return Optional.ofNullable(cacheById.get(id));
    }

    public Optional<LoadedWorld> getWorldByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cacheByName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<LoadedWorld> getWorldByMapId(String mapId) {
        if (mapId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cacheByMapId.get(mapId.toLowerCase(Locale.ROOT)));
    }

    public List<PoiDefinition> getWorldPois(String worldName) {
        return getWorldByName(worldName)
                .map(LoadedWorld::getPois)
                .map(Collections::unmodifiableList)
                .orElseGet(List::of);
    }

    public CompletableFuture<MapSaveResult> saveWorldDefinition(String serverId,
                                                                String worldName,
                                                                String displayName,
                                                                JsonObject metadata,
                                                                byte[] schematicBytes) {


        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName is required");
        }
        if (schematicBytes == null || schematicBytes.length == 0) {
            throw new IllegalArgumentException("schematicBytes cannot be empty");
        }

        JsonObject safeMetadata = metadata != null ? metadata : new JsonObject();
        String metadataJson = safeMetadata.toString();
        String sql = "INSERT INTO world_maps (world_name, display_name, map_metadata, schematic_data) "
                + "VALUES (?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (world_name) DO UPDATE "
                + "SET display_name = EXCLUDED.display_name, "
                + "map_metadata = EXCLUDED.map_metadata, "
                + "schematic_data = EXCLUDED.schematic_data, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "RETURNING id, updated_at;";

        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, worldName);
                if (displayName == null || displayName.isBlank()) {
                    statement.setNull(2, Types.VARCHAR);
                } else {
                    statement.setString(2, displayName);
                }
                statement.setString(3, metadataJson);
                statement.setBytes(4, schematicBytes);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        UUID id = resultSet.getObject("id", UUID.class);
                        Timestamp updated = resultSet.getTimestamp("updated_at");
                        Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                        logger.info("Persisted world map definition " + worldName + " (id=" + id + ")");
                        return new MapSaveResult(id, updatedAt);
                    }
                }

                throw new SQLException("Insert returned no rows for world map " + worldName);
            } catch (SQLException e) {
                throw new CompletionException("Failed to persist world map " + worldName, e);
            }
        });
    }

    private void loadWorlds() {
        Map<UUID, LoadedWorld> byId = new HashMap<>();
        Map<String, LoadedWorld> byName = new HashMap<>();
        Map<String, LoadedWorld> byMapId = new HashMap<>();

        String sql = "SELECT id, world_name, display_name, map_metadata, schematic_data, updated_at FROM world_maps";

        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                UUID id = resultSet.getObject("id", UUID.class);
                String worldName = resultSet.getString("world_name");
                String displayName = resultSet.getString("display_name");
                JsonObject metadata = readMetadata(resultSet.getString("map_metadata"));
                byte[] schematicBytes = resultSet.getBytes("schematic_data");
                Timestamp updated = resultSet.getTimestamp("updated_at");

                if (schematicBytes == null || schematicBytes.length == 0) {
                    logger.warning("Skipping world " + worldName + " - schematic_data column empty");
                    continue;
                }

                String mapId = metadata.has("mapId") ? metadata.get("mapId").getAsString() : id.toString();
                File schemFile = new File(cacheDirectory, mapId + ".schem");

                try {
                    InspectionResult result = inspector.inspect(schematicBytes, worldName != null ? worldName : mapId);
                    writeClipboard(schemFile, result.clipboard());
                    Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                    schemFile.setLastModified(updatedAt.toEpochMilli());

                    List<PoiDefinition> parsedPois = parsePois(metadata);
                    List<PoiDefinition> pois = parsedPois != null ? parsedPois : result.pois();
                    LoadedWorld loadedWorld = new LoadedWorld(
                            id,
                            worldName,
                            displayName,
                            metadata,
                            schemFile,
                            List.copyOf(pois),
                            updatedAt
                    );

                    byId.put(id, loadedWorld);
                    if (worldName != null) {
                        byName.put(worldName.toLowerCase(Locale.ROOT), loadedWorld);
                    }
                    byMapId.put(mapId.toLowerCase(Locale.ROOT), loadedWorld);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Failed to cache schematic for world " + worldName, exception);
                }
            }
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load world map definitions", exception);
            return;
        }

        cacheById.clear();
        cacheById.putAll(byId);
        cacheByName.clear();
        cacheByName.putAll(byName);
        cacheByMapId.clear();
        cacheByMapId.putAll(byMapId);

        logger.info("WorldService cached " + cacheById.size() + " world schematics");
    }

    private JsonObject readMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception exception) {
            logger.warning("Failed to parse map_metadata JSON: " + exception.getMessage());
            return new JsonObject();
        }
    }

    private List<PoiDefinition> parsePois(JsonObject metadata) {
        if (metadata == null || !metadata.has("pois")) {
            return null;
        }

        JsonElement root = metadata.get("pois");
        if (!(root instanceof JsonArray array)) {
            logger.warning("Ignoring malformed POI metadata (expected array)");
            return List.of();
        }

        List<PoiDefinition> pois = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : null;
            if (type == null || type.isBlank()) {
                continue;
            }
            String id = obj.has("id") ? obj.get("id").getAsString() : null;
            int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
            int y = obj.has("y") ? obj.get("y").getAsInt() : 0;
            int z = obj.has("z") ? obj.get("z").getAsInt() : 0;
            JsonObject extra = obj.has("metadata") && obj.get("metadata").isJsonObject()
                    ? obj.getAsJsonObject("metadata").deepCopy()
                    : new JsonObject();
            pois.add(new PoiDefinition(id, type, BlockVector3.at(x, y, z), extra));
        }
        return pois;
    }

    private void writeClipboard(File target, Clipboard clipboard) throws IOException {
        if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) {
            throw new IOException("Could not create directory for " + target.getAbsolutePath());
        }
        try (FileOutputStream outputStream = new FileOutputStream(target);
             var writer = SchematicInspector.defaultFormat().getWriter(outputStream)) {
            writer.write(clipboard);
        }
    }

    public record MapSaveResult(UUID id, Instant updatedAt) {
    }
}


