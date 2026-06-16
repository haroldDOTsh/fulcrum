package sh.harold.fulcrum.core.session;

@FunctionalInterface
public interface SessionReducer<S, E extends SessionDomainEvent> {
    SessionReduction<S> reduce(S state, E event);
}
