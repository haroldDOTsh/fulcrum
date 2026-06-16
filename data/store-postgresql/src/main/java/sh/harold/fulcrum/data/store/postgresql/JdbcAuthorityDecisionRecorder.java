package sh.harold.fulcrum.data.store.postgresql;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityCommandDelivery;
import sh.harold.fulcrum.data.authority.runtime.AuthorityDecisionRecorder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

public final class JdbcAuthorityDecisionRecorder<S, C extends CommandPayload, R>
        implements AuthorityDecisionRecorder<S, C, R> {
    private final DataSource dataSource;
    private final JdbcAuthorityDecisionRecorderConfig config;
    private final JdbcAuthorityDecisionPayloadEncoder<S, R> payloadEncoder;

    public JdbcAuthorityDecisionRecorder(
            DataSource dataSource,
            JdbcAuthorityDecisionRecorderConfig config,
            JdbcAuthorityDecisionPayloadEncoder<S, R> payloadEncoder) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.payloadEncoder = Objects.requireNonNull(payloadEncoder, "payloadEncoder");
    }

    @Override
    public void record(AuthorityCommandDelivery<C> delivery, AuthorityDecision<S, R> decision) {
        String sql = """
                INSERT INTO %s (
                    command_id,
                    aggregate_id,
                    source_topic,
                    source_partition,
                    source_offset,
                    status,
                    rejection_reason,
                    revision,
                    replayed,
                    trace_id,
                    decision_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(config.tableName());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, delivery.command().envelope().commandId().value());
            statement.setString(2, delivery.command().envelope().aggregateId().value());
            statement.setString(3, delivery.offset().source());
            statement.setInt(4, delivery.offset().partition());
            statement.setLong(5, delivery.offset().position());
            statement.setString(6, decision.status().name());
            statement.setString(7, decision.rejectionReason().map(Enum::name).orElse(""));
            statement.setLong(8, decision.revision().value());
            statement.setBoolean(9, decision.replayed());
            statement.setString(10, decision.traceEnvelope().traceId());
            statement.setString(11, payloadEncoder.encode(decision));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not record authority decision in PostgreSQL", exception);
        }
    }
}
