package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.api.contract.IdempotencyKey;
import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.EffectId;
import sh.harold.fulcrum.api.kernel.InstanceId;
import sh.harold.fulcrum.api.kernel.RouteId;
import sh.harold.fulcrum.api.kernel.SessionId;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectClassifier;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectOrigin;
import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectTargetScope;
import sh.harold.fulcrum.core.session.SessionReduction;
import sh.harold.fulcrum.host.api.HostObservation;
import sh.harold.fulcrum.host.api.HostObservationFactory;
import sh.harold.fulcrum.host.api.HostSecurityContext;
import sh.harold.fulcrum.host.api.HostSessionAttachment;
import sh.harold.fulcrum.host.api.HostSessionDetachment;
import sh.harold.fulcrum.host.tick.FilteringDomainEventBridge;
import sh.harold.fulcrum.host.tick.HostLocalEffectDispatcher;
import sh.harold.fulcrum.host.tick.HostMainThread;
import sh.harold.fulcrum.host.tick.HostTickRuntimeContext;
import sh.harold.fulcrum.host.tick.HostTickSessionRuntime;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class PaperJoinAttachmentHandler {
    private final HostSecurityContext securityContext;
    private final Supplier<SessionId> sessionIdSupplier;
    private final String routeIdPrefix;
    private final PaperObservationSink observationSink;
    private final Clock clock;
    private final HostMainThread mainThread;
    private final AtomicReference<RuntimeBinding> runtime = new AtomicReference<>();

    public PaperJoinAttachmentHandler(
            HostSecurityContext securityContext,
            SessionId sessionId,
            String routeIdPrefix,
            PaperObservationSink observationSink,
            Clock clock) {
        this(securityContext, sessionId, routeIdPrefix, observationSink, clock, new InlineHostMainThread());
    }

    public PaperJoinAttachmentHandler(
            HostSecurityContext securityContext,
            SessionId sessionId,
            String routeIdPrefix,
            PaperObservationSink observationSink,
            Clock clock,
            HostMainThread mainThread) {
        this(
                securityContext,
                () -> Objects.requireNonNull(sessionId, "sessionId"),
                routeIdPrefix,
                observationSink,
                clock,
                mainThread);
    }

    public PaperJoinAttachmentHandler(
            HostSecurityContext securityContext,
            Supplier<SessionId> sessionIdSupplier,
            String routeIdPrefix,
            PaperObservationSink observationSink,
            Clock clock,
            HostMainThread mainThread) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.routeIdPrefix = PaperArtifactNames.requireNonBlank(routeIdPrefix, "routeIdPrefix");
        this.observationSink = Objects.requireNonNull(observationSink, "observationSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    public HostObservation attach(PaperJoiningSubject subject) {
        return accept(PaperSessionHostEventType.ATTACHED, subject);
    }

    public HostObservation detach(PaperJoiningSubject subject) {
        return accept(PaperSessionHostEventType.DETACHED, subject);
    }

    PaperSessionRuntimeState state() {
        return runtimeBinding().runtime().state();
    }

    SessionId sessionId() {
        return runtimeBinding().sessionId();
    }

    InstanceId instanceId() {
        return securityContext.identity().instanceId();
    }

    private HostObservation accept(PaperSessionHostEventType type, PaperJoiningSubject subject) {
        Objects.requireNonNull(subject, "subject");
        Instant now = clock.instant();
        RuntimeBinding binding = runtimeBinding();
        PaperSessionHostEvent event = new PaperSessionHostEvent(
                type,
                subject,
                routeId(subject),
                binding.sessionId(),
                trace(type, subject, now),
                now);
        SessionReduction<PaperSessionRuntimeState> reduction = binding.runtime().acceptHostEvent(event)
                .orElseThrow(() -> new IllegalStateException("Paper Session host event was filtered unexpectedly"));
        return reduction.effects().stream()
                .map(EffectEnvelope::payload)
                .filter(PaperSessionObservationEffect.class::isInstance)
                .map(PaperSessionObservationEffect.class::cast)
                .map(PaperSessionObservationEffect::observation)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Paper Session host event did not emit an observation"));
    }

    private PaperSessionDomainEvent toDomainEvent(PaperSessionHostEvent event) {
        return new PaperSessionDomainEvent(
                event.type().eventType(),
                event.sessionId(),
                event.routeId(),
                event.subject().subjectId(),
                event.traceEnvelope(),
                event.occurredAt());
    }

    private SessionReduction<PaperSessionRuntimeState> reduce(
            PaperSessionRuntimeState state,
            PaperSessionDomainEvent event) {
        PaperSessionRuntimeState next = switch (event.eventType()) {
            case "paper.session-attached" -> state.attach(event.subjectId());
            case "paper.session-detached" -> state.detach(event.subjectId());
            default -> throw new IllegalArgumentException("Unsupported Paper Session event " + event.eventType());
        };
        return SessionReduction.withEffects(next, List.of(observationEffect(event)));
    }

    private EffectEnvelope<PaperSessionObservationEffect> observationEffect(PaperSessionDomainEvent event) {
        return EffectEnvelope.issue(
                new EffectId("effect-" + event.eventType() + "-" + event.subjectId().value()),
                new IdempotencyKey("idem-" + event.eventType() + "-" + event.subjectId().value()),
                EffectOrigin.session(event.sessionId()),
                event.traceEnvelope(),
                Optional.empty(),
                new EffectTargetScope("session:" + event.sessionId().value()),
                EffectClass.CONTROL_PLANE,
                new PaperSessionObservationEffect(observation(event)),
                event.occurredAt(),
                Optional.empty(),
                EffectSettlementMode.ACCEPTED_ASYNC);
    }

    private HostObservation observation(PaperSessionDomainEvent event) {
        if (PaperSessionHostEventType.ATTACHED.eventType().equals(event.eventType())) {
            HostSessionAttachment attachment = new HostSessionAttachment(
                    securityContext.identity(),
                    event.routeId(),
                    event.subjectId(),
                    event.sessionId(),
                    event.traceEnvelope(),
                    event.occurredAt());
            return HostObservationFactory.sessionAttached(attachment);
        }
        HostSessionDetachment detachment = new HostSessionDetachment(
                securityContext.identity(),
                event.routeId(),
                event.subjectId(),
                event.sessionId(),
                event.traceEnvelope(),
                event.occurredAt());
        return HostObservationFactory.sessionDetached(detachment);
    }

    private void publishPlatformEffect(EffectEnvelope<? extends EffectPayload> effect) {
        if (!(effect.payload() instanceof PaperSessionObservationEffect observationEffect)) {
            throw new IllegalArgumentException("Unsupported Paper platform effect " + effect.payloadType());
        }
        observationSink.publish(observationEffect.observation());
    }

    private RouteId routeId(PaperJoiningSubject subject) {
        return new RouteId(routeIdPrefix + subject.playerUuid());
    }

    private TraceEnvelope trace(PaperSessionHostEventType type, PaperJoiningSubject subject, Instant now) {
        String action = type == PaperSessionHostEventType.ATTACHED ? "attach" : "detach";
        return new TraceEnvelope(
                "trace-paper-" + action + "-" + subject.playerUuid(),
                "span-paper-" + action,
                Optional.empty(),
                now,
                "paper-agent",
                securityContext.identity().instanceId());
    }

    private RuntimeBinding runtimeBinding() {
        RuntimeBinding current = runtime.get();
        if (current != null) {
            return current;
        }
        SessionId resolvedSessionId = Objects.requireNonNull(sessionIdSupplier.get(), "sessionId");
        RuntimeBinding created = new RuntimeBinding(
                resolvedSessionId,
                new HostTickSessionRuntime<>(
                        new HostTickRuntimeContext(resolvedSessionId),
                        PaperSessionRuntimeState.empty(resolvedSessionId),
                        new FilteringDomainEventBridge<>(
                                ignored -> true,
                                this::toDomainEvent),
                        this::reduce,
                        new EffectClassifier(),
                        new HostLocalEffectDispatcher(mainThread),
                        ignored -> {
                            throw new IllegalStateException("Paper attach runtime does not emit host-local effects yet");
                        },
                        this::publishPlatformEffect));
        if (runtime.compareAndSet(null, created)) {
            return created;
        }
        return runtime.get();
    }

    private record RuntimeBinding(
            SessionId sessionId,
            HostTickSessionRuntime<PaperSessionRuntimeState, PaperSessionHostEvent, PaperSessionDomainEvent> runtime) {
        private RuntimeBinding {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            runtime = Objects.requireNonNull(runtime, "runtime");
        }
    }

    private static final class InlineHostMainThread implements HostMainThread {
        @Override
        public boolean isMainThread() {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            Objects.requireNonNull(task, "task").run();
        }
    }
}
