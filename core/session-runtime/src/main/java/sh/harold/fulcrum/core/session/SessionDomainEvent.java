package sh.harold.fulcrum.core.session;

import sh.harold.fulcrum.api.contract.TraceEnvelope;
import sh.harold.fulcrum.api.kernel.SessionId;

import java.time.Instant;

public interface SessionDomainEvent {
    String eventType();

    SessionId sessionId();

    TraceEnvelope traceEnvelope();

    Instant occurredAt();
}
