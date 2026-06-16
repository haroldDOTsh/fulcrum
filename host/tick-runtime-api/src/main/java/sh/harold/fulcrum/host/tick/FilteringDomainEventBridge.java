package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.core.session.SessionDomainEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public final class FilteringDomainEventBridge<H, E extends SessionDomainEvent> implements DomainEventBridge<H, E> {
    private final Predicate<? super H> meaningful;
    private final Function<? super H, ? extends E> mapper;

    public FilteringDomainEventBridge(Predicate<? super H> meaningful, Function<? super H, ? extends E> mapper) {
        this.meaningful = Objects.requireNonNull(meaningful, "meaningful");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<E> translate(H hostEvent) {
        H checkedEvent = Objects.requireNonNull(hostEvent, "hostEvent");
        if (!meaningful.test(checkedEvent)) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(mapper.apply(checkedEvent), "domainEvent"));
    }
}
