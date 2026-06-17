package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.core.session.ClassifiedEffect;
import sh.harold.fulcrum.core.session.EffectClassifier;
import sh.harold.fulcrum.core.session.EffectDestination;
import sh.harold.fulcrum.core.session.SessionDomainEvent;
import sh.harold.fulcrum.core.session.SessionReducer;
import sh.harold.fulcrum.core.session.SessionReduction;

import java.util.Objects;
import java.util.Optional;

public final class HostTickSessionRuntime<S, H, E extends SessionDomainEvent> {
    private final HostTickRuntimeContext context;
    private final DomainEventBridge<H, E> domainEventBridge;
    private final SessionReducer<S, E> reducer;
    private final EffectClassifier effectClassifier;
    private final HostLocalEffectDispatcher hostLocalEffectDispatcher;
    private final HostLocalEffectHandler hostLocalEffectHandler;
    private final PlatformEffectSink platformEffectSink;
    private S state;

    public HostTickSessionRuntime(
            HostTickRuntimeContext context,
            S initialState,
            DomainEventBridge<H, E> domainEventBridge,
            SessionReducer<S, E> reducer,
            EffectClassifier effectClassifier,
            HostLocalEffectDispatcher hostLocalEffectDispatcher,
            HostLocalEffectHandler hostLocalEffectHandler,
            PlatformEffectSink platformEffectSink) {
        this.context = Objects.requireNonNull(context, "context");
        this.state = Objects.requireNonNull(initialState, "initialState");
        this.domainEventBridge = Objects.requireNonNull(domainEventBridge, "domainEventBridge");
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.effectClassifier = Objects.requireNonNull(effectClassifier, "effectClassifier");
        this.hostLocalEffectDispatcher = Objects.requireNonNull(hostLocalEffectDispatcher, "hostLocalEffectDispatcher");
        this.hostLocalEffectHandler = Objects.requireNonNull(hostLocalEffectHandler, "hostLocalEffectHandler");
        this.platformEffectSink = Objects.requireNonNull(platformEffectSink, "platformEffectSink");
    }

    public Optional<SessionReduction<S>> acceptHostEvent(H hostEvent) {
        return domainEventBridge.translate(hostEvent).map(this::applyDomainEvent);
    }

    public SessionReduction<S> applyDomainEvent(E event) {
        Objects.requireNonNull(event, "event");
        if (!context.sessionId().equals(event.sessionId())) {
            throw new IllegalArgumentException("Domain event Session does not match attached Session");
        }
        SessionReduction<S> reduction = reducer.reduce(state, event);
        state = reduction.state();
        for (var effect : reduction.effects()) {
            ClassifiedEffect classified = effectClassifier.classify(effect);
            if (classified.destination() == EffectDestination.HOST_LOCAL) {
                hostLocalEffectDispatcher.dispatch(classified.effect(), hostLocalEffectHandler);
            } else {
                platformEffectSink.emit(classified.effect());
            }
        }
        return reduction;
    }

    public S state() {
        return state;
    }

    public HostTickRuntimeContext context() {
        return context;
    }
}
