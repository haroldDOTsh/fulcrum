package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectPayload;

@FunctionalInterface
public interface HostLocalEffectHandler {
    void handle(EffectEnvelope<? extends EffectPayload> effect);
}
