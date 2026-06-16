package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.core.session.SessionDomainEvent;

import java.util.Optional;

@FunctionalInterface
public interface DomainEventBridge<H, E extends SessionDomainEvent> {
    Optional<E> translate(H hostEvent);
}
