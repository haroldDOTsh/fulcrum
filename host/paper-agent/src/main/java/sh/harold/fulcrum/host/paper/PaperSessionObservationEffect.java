package sh.harold.fulcrum.host.paper;

import sh.harold.fulcrum.core.session.EffectPayload;
import sh.harold.fulcrum.host.api.HostObservation;

import java.util.Objects;

record PaperSessionObservationEffect(HostObservation observation) implements EffectPayload {
    static final String PAYLOAD_TYPE = "paper.session-observation";

    PaperSessionObservationEffect {
        observation = Objects.requireNonNull(observation, "observation");
    }

    @Override
    public String payloadType() {
        return PAYLOAD_TYPE;
    }
}
