package sh.harold.fulcrum.data.store.postgresql;

import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;

public final class JdbcAuthorityRecordStore<S> implements AuthorityRecordStore<S> {
    private final DataSource dataSource;
    private final JdbcAuthorityRecordStoreConfig config;
    private final JdbcAuthorityStateCodec<S> codec;
    private final Supplier<AuthorityRecord<S>> emptyRecord;

    public JdbcAuthorityRecordStore(
            DataSource dataSource,
            JdbcAuthorityRecordStoreConfig config,
            JdbcAuthorityStateCodec<S> codec,
            Supplier<AuthorityRecord<S>> emptyRecord) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.emptyRecord = Objects.requireNonNull(emptyRecord, "emptyRecord");
    }

    @Override
    public AuthorityRecord<S> load(AggregateId aggregateId) {
        String sql = "SELECT revision, fencing_epoch, state_payload FROM %s WHERE aggregate_id = ?"
                .formatted(config.tableName());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, aggregateId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return emptyRecord.get();
                }
                return new AuthorityRecord<>(
                        new Revision(result.getLong("revision")),
                        result.getLong("fencing_epoch"),
                        codec.decode(result.getString("state_payload")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load authority record from PostgreSQL", exception);
        }
    }

    @Override
    public void store(AggregateId aggregateId, AuthorityRecord<S> record) {
        String sql = """
                INSERT INTO %s (aggregate_id, revision, fencing_epoch, state_payload)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (aggregate_id) DO UPDATE SET
                    revision = EXCLUDED.revision,
                    fencing_epoch = EXCLUDED.fencing_epoch,
                    state_payload = EXCLUDED.state_payload
                """.formatted(config.tableName());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, aggregateId.value());
            statement.setLong(2, record.revision().value());
            statement.setLong(3, record.fencingEpoch());
            statement.setString(4, codec.encode(record.state()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not store authority record in PostgreSQL", exception);
        }
    }
}
