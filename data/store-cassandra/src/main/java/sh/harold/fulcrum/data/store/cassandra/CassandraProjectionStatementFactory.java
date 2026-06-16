package sh.harold.fulcrum.data.store.cassandra;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;

@FunctionalInterface
public interface CassandraProjectionStatementFactory<S, C extends CommandPayload, R> {
    SimpleStatement statementFor(AuthorityCommand<C> command, AuthorityDecision<S, R> decision);
}
