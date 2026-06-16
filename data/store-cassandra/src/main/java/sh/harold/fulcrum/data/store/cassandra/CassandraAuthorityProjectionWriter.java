package sh.harold.fulcrum.data.store.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;

import java.util.Objects;

public final class CassandraAuthorityProjectionWriter<S, C extends CommandPayload, R>
        implements AuthorityProjectionWriter<S, C, R> {
    private final CqlSession session;
    private final CassandraProjectionStatementFactory<S, C, R> statementFactory;

    public CassandraAuthorityProjectionWriter(
            CqlSession session,
            CassandraProjectionStatementFactory<S, C, R> statementFactory) {
        this.session = Objects.requireNonNull(session, "session");
        this.statementFactory = Objects.requireNonNull(statementFactory, "statementFactory");
    }

    @Override
    public void write(AuthorityCommand<C> command, AuthorityDecision<S, R> decision) {
        SimpleStatement statement = statementFactory.statementFor(command, decision);
        session.execute(statement);
    }
}
