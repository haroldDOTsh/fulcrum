package sh.harold.fulcrum.host.tick;

import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectPayload;

import java.util.Objects;

public final class HostLocalEffectDispatcher {
    private final HostMainThread mainThread;

    public HostLocalEffectDispatcher(HostMainThread mainThread) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    public void dispatch(EffectEnvelope<? extends EffectPayload> effect, HostLocalEffectHandler handler) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(handler, "handler");
        if (effect.effectClass() != EffectClass.HOST_LOCAL) {
            throw new IllegalArgumentException("Only host-local Effects can execute in the host tick runtime");
        }
        Runnable task = () -> handler.handle(effect);
        if (mainThread.isMainThread()) {
            task.run();
        } else {
            mainThread.execute(task);
        }
    }
}
