package sh.harold.fulcrum.validation.auctionescrow;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.valkey.UnifiedJedis;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.data.authority.AuthorityRecord;
import sh.harold.fulcrum.data.authority.runtime.AuthorityEmissionSinks;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRecordStore;
import sh.harold.fulcrum.data.authority.runtime.AuthorityRuntimeWorker;
import sh.harold.fulcrum.data.store.cassandra.CassandraAuthorityProjectionWriter;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityCommandSource;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionSink;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityEmissionTopics;
import sh.harold.fulcrum.data.store.kafka.KafkaAuthorityOffsetCommitter;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorder;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityDecisionRecorderConfig;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStore;
import sh.harold.fulcrum.data.store.postgresql.JdbcAuthorityRecordStoreConfig;
import sh.harold.fulcrum.data.store.valkey.ValkeyAuthorityCacheSink;
import sh.harold.fulcrum.data.store.valkey.ValkeyIdempotencyLedger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class AuctionEscrowStoreBackedRuntime {
    static final String RECORD_TABLE = "auction_escrow_authority_records";
    static final String DECISION_TABLE = "auction_escrow_authority_decisions";
    static final String CASSANDRA_PROJECTION_TABLE = "fulcrum.auction_escrow_projection";
    static final String IDEMPOTENCY_PREFIX = "auction-escrow:idempotency";

    private AuctionEscrowStoreBackedRuntime() {
    }

    static AuthorityRuntimeWorker<AuctionEscrowState, AuctionEscrowCommand, AuctionEscrowReceipt> worker(
            Consumer<String, String> consumer,
            Producer<String, String> producer,
            CqlSession cqlSession,
            UnifiedJedis valkey,
            DataSource dataSource,
            long fencingEpoch,
            KafkaAuthorityEmissionTopics topics) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(cqlSession, "cqlSession");
        Objects.requireNonNull(valkey, "valkey");
        Objects.requireNonNull(dataSource, "dataSource");
        AuctionEscrowAuthority authority = new AuctionEscrowAuthority(new ValkeyIdempotencyLedger<>(
                valkey,
                IDEMPOTENCY_PREFIX,
                new AuctionEscrowStoredDecisionCodec(),
                Optional.of(Duration.ofMinutes(10))));
        AuthorityRecordStore<AuctionEscrowState> recordStore = new EpochAdoptingRecordStore(
                new JdbcAuthorityRecordStore<>(
                        dataSource,
                        new JdbcAuthorityRecordStoreConfig(RECORD_TABLE),
                        AuctionEscrowStateStoreCodec.INSTANCE,
                        () -> new AuthorityRecord<>(new Revision(0), fencingEpoch, AuctionEscrowState.empty())),
                fencingEpoch);
        return new AuthorityRuntimeWorker<>(
                new KafkaAuthorityCommandSource<>(consumer, Duration.ofSeconds(10), AuctionEscrowCommandWireCodec::decode),
                recordStore,
                authority::handle,
                new CassandraAuthorityProjectionWriter<>(
                        cqlSession,
                        (command, decision) -> {
                            EscrowSnapshot snapshot = decision.state().current().orElseThrow();
                            long totalReleased = decision.response().totalReleasedMinor().orElse(0L);
                            return SimpleStatement.newInstance(
                                    "INSERT INTO " + CASSANDRA_PROJECTION_TABLE
                                            + " (aggregate_id, auction_id, status, total_held_minor, total_released_minor, revision) VALUES (?, ?, ?, ?, ?, ?)",
                                    command.envelope().aggregateId().value(),
                                    snapshot.auctionId(),
                                    snapshot.status().name(),
                                    decision.response().totalHeldMinor().orElse(0L),
                                    totalReleased,
                                    decision.revision().value());
                        }),
                AuthorityEmissionSinks.composite(
                        new KafkaAuthorityEmissionSink(
                                producer,
                                Objects.requireNonNull(topics, "topics"),
                                Duration.ofSeconds(10)),
                        new ValkeyAuthorityCacheSink(valkey)),
                new JdbcAuthorityDecisionRecorder<>(
                        dataSource,
                        new JdbcAuthorityDecisionRecorderConfig(DECISION_TABLE),
                        decision -> decision.response().wireValue()),
                new KafkaAuthorityOffsetCommitter(consumer));
    }

    static long effectiveFencingEpoch(DataSource dataSource, long admittedFencingEpoch) {
        return effectiveFencingEpoch(maxFencingEpoch(dataSource), admittedFencingEpoch);
    }

    static long effectiveFencingEpoch(long storedFencingEpoch, long admittedFencingEpoch) {
        if (admittedFencingEpoch <= 0) {
            throw new IllegalArgumentException("admittedFencingEpoch must be positive");
        }
        if (storedFencingEpoch < 0) {
            throw new IllegalArgumentException("storedFencingEpoch must be non-negative");
        }
        return storedFencingEpoch >= admittedFencingEpoch
                ? storedFencingEpoch + 1
                : admittedFencingEpoch;
    }

    static long maxFencingEpoch(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(fencing_epoch), 0) FROM " + RECORD_TABLE + ";")) {
            if (!result.next()) {
                return 0;
            }
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read auction escrow fencing epoch", exception);
        }
    }

    private static final class EpochAdoptingRecordStore implements AuthorityRecordStore<AuctionEscrowState> {
        private final AuthorityRecordStore<AuctionEscrowState> delegate;
        private final long runtimeFencingEpoch;

        private EpochAdoptingRecordStore(
                AuthorityRecordStore<AuctionEscrowState> delegate,
                long runtimeFencingEpoch) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            if (runtimeFencingEpoch <= 0) {
                throw new IllegalArgumentException("runtimeFencingEpoch must be positive");
            }
            this.runtimeFencingEpoch = runtimeFencingEpoch;
        }

        @Override
        public AuthorityRecord<AuctionEscrowState> load(AggregateId aggregateId) {
            AuthorityRecord<AuctionEscrowState> record = delegate.load(aggregateId);
            if (record.fencingEpoch() >= runtimeFencingEpoch) {
                return record;
            }
            return new AuthorityRecord<>(record.revision(), runtimeFencingEpoch, record.state());
        }

        @Override
        public void store(
                AggregateId aggregateId,
                AuthorityRecord<AuctionEscrowState> record) {
            delegate.store(aggregateId, record);
        }
    }
}
