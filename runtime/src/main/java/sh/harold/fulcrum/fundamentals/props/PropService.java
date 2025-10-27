package sh.harold.fulcrum.fundamentals.props;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.fundamentals.props.model.PropDefinition;
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
 * Service for hydrating prop schematics from PostgreSQL and caching them locally.
 */
public class PropService {

    private final Plugin plugin;
    private final PostgresConnectionAdapter connectionAdapter;
    private final Logger logger;
    private final File cacheDirectory;
    private final SchematicInspector inspector;
    private final Map<UUID, PropDefinition> cacheById = new ConcurrentHashMap<>();
    private final Map<String, PropDefinition> cacheByName = new ConcurrentHashMap<>();
    private final Map<String, List<PropDefinition>> cacheByType = new ConcurrentHashMap<>();

    public PropService(Plugin plugin, PostgresConnectionAdapter connectionAdapter) {
        this.plugin = plugin;
        this.connectionAdapter = connectionAdapter;
        this.logger = plugin.getLogger();
        this.cacheDirectory = new File(plugin.getDataFolder(), "prop-cache");
        this.inspector = new SchematicInspector(logger);
    }

    public CompletableFuture<Void> initialize() {
        if (SchematicInspector.defaultFormat() == null) {
            throw new IllegalStateException("PropService requires FAWE .schem support");
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            logger.warning("Failed to create prop cache directory: " + cacheDirectory.getAbsolutePath());
        }
        return refreshCache();
    }

    public CompletableFuture<Void> refreshCache() {
        return CompletableFuture.runAsync(this::loadProps);
    }

    public void shutdown() {
        cacheById.clear();
        cacheByName.clear();
        cacheByType.clear();
    }

    public int getCachedPropCount() {
        return cacheById.size();
    }

    public Optional<PropDefinition> getPropById(UUID id) {
        return Optional.ofNullable(cacheById.get(id));
    }

    public Optional<PropDefinition> getPropByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cacheByName.get(name.toLowerCase(Locale.ROOT)));
    }

    public List<PropDefinition> getPropsByType(String type) {
        if (type == null) {
            return List.of();
        }
        return cacheByType.getOrDefault(type.toLowerCase(Locale.ROOT), List.of());
    }

    public CompletableFuture<PropSaveResult> savePropDefinition(String propName,
                                                                String displayName,
                                                                String propType,
                                                                JsonObject metadata,
                                                                byte[] schematicBytes) {
        if (propName == null || propName.isBlank()) {
            throw new IllegalArgumentException("propName is required");
        }
        if (propType == null || propType.isBlank()) {
            throw new IllegalArgumentException("propType is required");
        }
        if (schematicBytes == null || schematicBytes.length == 0) {
            throw new IllegalArgumentException("schematicBytes cannot be empty");
        }

        JsonObject safeMetadata = metadata != null ? metadata : new JsonObject();
        String metadataJson = safeMetadata.toString();
        String sql = "INSERT INTO world_props (prop_name, display_name, prop_type, prop_metadata, schematic_data) "
                + "VALUES (?, ?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (prop_name) DO UPDATE "
                + "SET display_name = EXCLUDED.display_name, "
                + "prop_type = EXCLUDED.prop_type, "
                + "prop_metadata = EXCLUDED.prop_metadata, "
                + "schematic_data = EXCLUDED.schematic_data, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "RETURNING id, updated_at;";

        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connectionAdapter.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {

                statement.setString(1, propName);
                if (displayName == null || displayName.isBlank()) {
                    statement.setNull(2, Types.VARCHAR);
                } else {
                    statement.setString(2, displayName);
                }
                statement.setString(3, propType);
                statement.setString(4, metadataJson);
                statement.setBytes(5, schematicBytes);

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        UUID id = resultSet.getObject("id", UUID.class);
                        Timestamp updated = resultSet.getTimestamp("updated_at");
                        Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                        logger.info("Persisted prop definition " + propName + " (id=" + id + ")");
                        return new PropSaveResult(id, updatedAt);
                    }
                }

                throw new SQLException("Insert returned no rows for prop " + propName);
            } catch (SQLException e) {
                throw new CompletionException("Failed to persist prop " + propName, e);
            }
        });
    }

    private void loadProps() {
        Map<UUID, PropDefinition> byId = new HashMap<>();
        Map<String, PropDefinition> byName = new HashMap<>();
        Map<String, List<PropDefinition>> byType = new HashMap<>();

        String sql = "SELECT id, prop_name, display_name, prop_type, prop_metadata, schematic_data, updated_at "
                + "FROM world_props";

        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                UUID id = resultSet.getObject("id", UUID.class);
                String propName = resultSet.getString("prop_name");
                String displayName = resultSet.getString("display_name");
                String propType = resultSet.getString("prop_type");
                JsonObject metadata = readMetadata(resultSet.getString("prop_metadata"));
                byte[] schematicBytes = resultSet.getBytes("schematic_data");
                Timestamp updated = resultSet.getTimestamp("updated_at");

                if (schematicBytes == null || schematicBytes.length == 0) {
                    logger.warning("Skipping prop " + propName + " - schematic_data column empty");
                    continue;
                }

                String safeName = sanitise(propName);
                File schemFile = new File(cacheDirectory, safeName + ".schem");

                try {
                    InspectionResult result = inspector.inspect(schematicBytes, propName);
                    writeClipboard(schemFile, result.clipboard());
                    Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                    schemFile.setLastModified(updatedAt.toEpochMilli());

                    List<PoiDefinition> pois = parsePois(metadata, result.pois());
                    PropDefinition prop = new PropDefinition(
                            id,
                            propName,
                            displayName,
                            propType,
                            metadata,
                            schemFile,
                            pois,
                            updatedAt
                    );

                    byId.put(id, prop);
                    byName.put(propName.toLowerCase(Locale.ROOT), prop);
                    byType.computeIfAbsent(propType.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(prop);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Failed to cache schematic for prop " + propName, exception);
                }
            }
        } catch (SQLException exception) {
            logger.log(Level.SEVERE, "Failed to load prop definitions", exception);
            return;
        }

        cacheById.clear();
        cacheById.putAll(byId);
        cacheByName.clear();
        cacheByName.putAll(byName);
        cacheByType.clear();
        byType.forEach((key, value) -> cacheByType.put(key, List.copyOf(value)));

        logger.info("PropService cached " + cacheById.size() + " props");
    }

    private JsonObject readMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception exception) {
            logger.warning("Failed to parse prop_metadata JSON: " + exception.getMessage());
            return new JsonObject();
        }
    }

    private List<PoiDefinition> parsePois(JsonObject metadata, List<PoiDefinition> inspectorPois) {
        if (metadata == null || !metadata.has("pois")) {
            return inspectorPois != null ? inspectorPois : List.of();
        }
        JsonElement root = metadata.get("pois");
        if (!(root instanceof JsonArray array)) {
            logger.warning("Ignoring malformed POI metadata for prop (expected array)");
            return inspectorPois != null ? inspectorPois : List.of();
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
        return pois.isEmpty() && inspectorPois != null ? inspectorPois : pois;
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

    private String sanitise(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record PropSaveResult(UUID id, Instant updatedAt) {
    }
}
