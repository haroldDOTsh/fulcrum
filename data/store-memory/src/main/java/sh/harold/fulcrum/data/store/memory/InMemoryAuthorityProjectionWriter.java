package sh.harold.fulcrum.data.store.memory;

import sh.harold.fulcrum.api.contract.CommandPayload;
import sh.harold.fulcrum.data.authority.AuthorityCommand;
import sh.harold.fulcrum.data.authority.AuthorityDecision;
import sh.harold.fulcrum.data.authority.runtime.AuthorityProjectionWriter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

public final class InMemoryAuthorityProjectionWriter<S, C extends CommandPayload, R>
        implements AuthorityProjectionWriter<S, C, R> {
    private final BiFunction<AuthorityCommand<C>, AuthorityDecision<S, R>, Projection> projector;
    private final Map<String, String> projections = new LinkedHashMap<>();

    public InMemoryAuthorityProjectionWriter(
            BiFunction<AuthorityCommand<C>, AuthorityDecision<S, R>, Projection> projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public synchronized void write(AuthorityCommand<C> command, AuthorityDecision<S, R> decision) {
        Projection projection = Objects.requireNonNull(projector.apply(command, decision), "projection");
        projections.put(projection.key(), projection.payload());
    }

    public synchronized Optional<String> find(String key) {
        return Optional.ofNullable(projections.get(key));
    }

    public record Projection(String key, String payload) {
        public Projection {
            key = requireNonBlank(key, "key");
            payload = Objects.requireNonNull(payload, "payload");
        }

        private static String requireNonBlank(String value, String label) {
            String checked = Objects.requireNonNull(value, label).trim();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return checked;
        }
    }
}
