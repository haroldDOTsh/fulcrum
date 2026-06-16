package sh.harold.fulcrum.data.store.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.api.contract.AggregateId;
import sh.harold.fulcrum.api.contract.CommandEnvelope;
import sh.harold.fulcrum.api.contract.CommandId;
import sh.harold.fulcrum.api.contract.CommandName;
import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.api.contract.ContractName;
import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.PrincipalId;
import sh.harold.fulcrum.api.contract.Revision;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;

final class CassandraAuthorityProjectionWriterTest {
    @Test
    void writerExecutesStatementFromFactory() {
        AtomicReference<Object> executed = new AtomicReference<>();
        CqlSession session = (CqlSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CqlSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("execute")) {
                        executed.set(args[0]);
                        return Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[]{ResultSet.class},
                                (resultProxy, resultMethod, resultArgs) -> null);
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    return null;
                });
        SimpleStatement statement = SimpleStatement.newInstance("INSERT INTO fulcrum.presence_hot (subject_id) VALUES (?)", "subject-1");

        new CassandraAuthorityProjectionWriter<String, TestPayload, String>(
                session,
                (command, decision) -> statement)
                .write(command(), decision());

        assertSame(statement, executed.get());
    }

    private static AuthorityCommand<TestPayload> command() {
        Instant now = Instant.parse("2026-06-16T00:00:00Z");
        return new AuthorityCommand<>(
                new CommandEnvelope<>(
                        new CommandId("command-cassandra"),
                        new IdempotencyKey("idempotency-cassandra"),
                        new PrincipalId("principal-cassandra"),
                        new AggregateId("aggregate-cassandra"),
                        new ContractName("contract-cassandra"),
                        new CommandName("command-cassandra"),
                        trace(now),
                        Optional.empty(),
                        new TestPayload()),
                new PrincipalId("principal-cassandra"),
                1,
                Optional.empty(),
                "payload-fingerprint-cassandra",
                now);
    }

    private static AuthorityDecision<String, String> decision() {
        return AuthorityDecision.accepted(new Revision(1), "state", "response", List.of(), trace(Instant.parse("2026-06-16T00:00:00Z")));
    }

    private static TraceEnvelope trace(Instant now) {
        return new TraceEnvelope(
                "trace-cassandra",
                "span-cassandra",
                Optional.empty(),
                now,
                "store-cassandra-test",
                new InstanceId("instance-store-cassandra-test"));
    }

    private record TestPayload() implements CommandPayload {
    }
}
