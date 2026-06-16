package sh.harold.fulcrum.host.tick;

import org.junit.jupiter.api.Test;
import sh.harold.fulcrum.core.session.EffectClass;
import sh.harold.fulcrum.core.session.EffectEnvelope;
import sh.harold.fulcrum.core.session.EffectSettlementMode;
import sh.harold.fulcrum.core.session.EffectPayload;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HostLocalEffectDispatcherTest {
    @Test
    void hostLocalEffectRunsInlineWhenAlreadyOnMainThread() {
        RecordingMainThread mainThread = new RecordingMainThread(true);
        List<EffectEnvelope<? extends EffectPayload>> handled = new ArrayList<>();

        new HostLocalEffectDispatcher(mainThread).dispatch(TickRuntimeFixtures.effect(EffectClass.HOST_LOCAL, EffectSettlementMode.HOST_INLINE), handled::add);

        assertEquals(1, handled.size());
        assertEquals(0, mainThread.queued.size());
    }

    @Test
    void hostLocalEffectIsQueuedWhenOffMainThread() {
        RecordingMainThread mainThread = new RecordingMainThread(false);
        List<EffectEnvelope<? extends EffectPayload>> handled = new ArrayList<>();

        new HostLocalEffectDispatcher(mainThread).dispatch(TickRuntimeFixtures.effect(EffectClass.HOST_LOCAL, EffectSettlementMode.HOST_INLINE), handled::add);

        assertEquals(0, handled.size());
        assertEquals(1, mainThread.queued.size());

        mainThread.queued.getFirst().run();
        assertEquals(1, handled.size());
    }

    @Test
    void platformEffectCannotExecuteInlineInHostRuntime() {
        RecordingMainThread mainThread = new RecordingMainThread(true);
        HostLocalEffectDispatcher dispatcher = new HostLocalEffectDispatcher(mainThread);

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(
                TickRuntimeFixtures.effect(EffectClass.AUTHORITY, EffectSettlementMode.ACCEPTED_ASYNC),
                ignored -> {
                }));
    }

    private static final class RecordingMainThread implements HostMainThread {
        private final boolean mainThread;
        private final List<Runnable> queued = new ArrayList<>();

        private RecordingMainThread(boolean mainThread) {
            this.mainThread = mainThread;
        }

        @Override
        public boolean isMainThread() {
            return mainThread;
        }

        @Override
        public void execute(Runnable task) {
            queued.add(task);
        }
    }
}
