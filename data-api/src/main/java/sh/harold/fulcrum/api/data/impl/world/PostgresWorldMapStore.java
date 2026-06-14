package sh.harold.fulcrum.api.data.impl.world;

import sh.harold.fulcrum.api.data.impl.postgres.FulcrumSchemaContract;
import sh.harold.fulcrum.api.data.impl.postgres.PostgresConnectionAdapter;
import sh.harold.fulcrum.api.data.world.WorldMapStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public final class PostgresWorldMapStore implements WorldMapStore {
    private static final String REGISTRY_SERVICE_ID = "registry-service";
    private static final String WORLD_MAPS_TABLE = "world_maps";

    private static final String LOAD_SQL =
        "SELECT id, world_name, display_name, map_metadata::text AS map_metadata, schematic_data, updated_at "
            + "FROM world_maps";

    private static final String SAVE_SQL =
        "INSERT INTO world_maps (world_name, display_name, map_metadata, schematic_data) "
            + "VALUES (?, ?, ?::jsonb, ?) "
            + "ON CONFLICT (world_name) DO UPDATE "
            + "SET display_name = EXCLUDED.display_name, "
            + "map_metadata = EXCLUDED.map_metadata, "
            + "schematic_data = EXCLUDED.schematic_data, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "RETURNING id, updated_at";

    private final PostgresConnectionAdapter connectionAdapter;
    private final Executor executor;

    public PostgresWorldMapStore(PostgresConnectionAdapter connectionAdapter) {
        this(connectionAdapter, ForkJoinPool.commonPool());
    }

    public PostgresWorldMapStore(PostgresConnectionAdapter connectionAdapter, Executor executor) {
        this.connectionAdapter = Objects.requireNonNull(connectionAdapter, "connectionAdapter");
        this.executor = executor == null ? ForkJoinPool.commonPool() : executor;
        validateSchemaContract(FulcrumSchemaContract.loadDefault());
    }

    static void validateSchemaContract(FulcrumSchemaContract contract) {
        Objects.requireNonNull(contract, "contract")
            .requireDataApiOwnedTable(WORLD_MAPS_TABLE, REGISTRY_SERVICE_ID);
    }

    @Override
    public CompletionStage<List<WorldMapRecord>> loadWorlds() {
        return CompletableFuture.supplyAsync(this::loadWorldRecords, executor);
    }

    @Override
    public CompletionStage<MapSaveResult> saveWorldDefinition(
        String serverId,
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        validateSave(worldName, schematicBytes);
        String safeMetadata = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
        byte[] safeSchematicBytes = schematicBytes.clone();

        return CompletableFuture.supplyAsync(() -> saveWorldRecord(worldName, displayName, safeMetadata, safeSchematicBytes),
            executor);
    }

    private List<WorldMapRecord> loadWorldRecords() {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(LOAD_SQL);
             ResultSet resultSet = statement.executeQuery()) {

            List<WorldMapRecord> records = new ArrayList<>();
            while (resultSet.next()) {
                Timestamp updated = resultSet.getTimestamp("updated_at");
                Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                records.add(new WorldMapRecord(
                    resultSet.getObject("id", java.util.UUID.class),
                    resultSet.getString("world_name"),
                    resultSet.getString("display_name"),
                    resultSet.getString("map_metadata"),
                    resultSet.getBytes("schematic_data"),
                    updatedAt
                ));
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new CompletionException("Failed to load world map definitions", exception);
        }
    }

    private MapSaveResult saveWorldRecord(
        String worldName,
        String displayName,
        String metadataJson,
        byte[] schematicBytes
    ) {
        try (Connection connection = connectionAdapter.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE_SQL)) {

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
                    Timestamp updated = resultSet.getTimestamp("updated_at");
                    Instant updatedAt = updated != null ? updated.toInstant() : Instant.now();
                    return new MapSaveResult(resultSet.getObject("id", java.util.UUID.class), updatedAt);
                }
            }
            throw new SQLException("Insert returned no rows for world map " + worldName);
        } catch (SQLException exception) {
            throw new CompletionException("Failed to persist world map " + worldName, exception);
        }
    }

    private static void validateSave(String worldName, byte[] schematicBytes) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName is required");
        }
        if (schematicBytes == null || schematicBytes.length == 0) {
            throw new IllegalArgumentException("schematicBytes cannot be empty");
        }
    }
}
