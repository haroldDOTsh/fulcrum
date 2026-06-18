package sh.harold.fulcrum.host.paper;

import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;

public final class FulcrumPaperPlugin extends JavaPlugin {
    private PaperHostMainThread mainThread;
    private PaperPlayerSessionListener sessionListener;
    private PaperObservationSink observationSink;
    private PaperCapabilityBridge capabilityBridge;
    private PaperRewardSink rewardSink;

    @Override
    public void onEnable() {
        PaperPluginRuntimeConfiguration configuration =
                PaperPluginRuntimeConfiguration.fromEnvironment(System.getenv());
        mainThread = new PaperHostMainThread(this);
        observationSink = createObservationSink(configuration);
        capabilityBridge = createCapabilityBridge(configuration);
        rewardSink = createRewardSink(configuration);
        getServer().getMessenger().registerOutgoingPluginChannel(this, PaperLobbyProofMessage.CHANNEL);
        sessionListener = new PaperPlayerSessionListener(
                this,
                new PaperJoinAttachmentHandler(
                        configuration.securityContext(),
                        () -> PaperAllocatedAssignmentFile.requireSessionId(configuration.allocatedAssignmentFile()),
                        configuration.routeIdPrefix(),
                        observationSink,
                        Clock.systemUTC(),
                        mainThread),
                () -> PaperAllocatedAssignmentFile.requireSlotId(configuration.allocatedAssignmentFile()),
                () -> PaperAllocatedAssignmentFile.requireResolvedManifestId(configuration.allocatedAssignmentFile()),
                () -> PaperAllocatedAssignmentFile.requireTraceId(configuration.allocatedAssignmentFile()),
                configuration.spawnPoint(),
                capabilityBridge,
                rewardSink);
        getServer().getPluginManager().registerEvents(sessionListener, this);
    }

    private PaperObservationSink createObservationSink(PaperPluginRuntimeConfiguration configuration) {
        return configuration.observationBridgeUrl()
                .<PaperObservationSink>map(PaperHttpObservationSink::new)
                .orElseGet(() -> observation -> getLogger().info(
                        "published " + observation.observationType()
                                + " for " + observation.instanceId().value()
                                + " " + observation.attributes()));
    }

    private PaperCapabilityBridge createCapabilityBridge(PaperPluginRuntimeConfiguration configuration) {
        return configuration.capabilityBridgeUrl()
                .<PaperCapabilityBridge>map(PaperCapabilityBridgeClient::new)
                .orElseGet(NoopPaperCapabilityBridge::new);
    }

    private PaperRewardSink createRewardSink(PaperPluginRuntimeConfiguration configuration) {
        return configuration.rewardBridgeUrl()
                .<PaperRewardSink>map(PaperHttpRewardSink::new)
                .orElseGet(() -> report -> getLogger().info(
                        "reported Paper reward for "
                                + report.subjectId().value()
                                + " in "
                                + report.sessionId().value()));
    }

    PaperHostMainThread mainThread() {
        return mainThread;
    }

    PaperPlayerSessionListener sessionListener() {
        return sessionListener;
    }

    PaperObservationSink observationSink() {
        return observationSink;
    }

    PaperCapabilityBridge capabilityBridge() {
        return capabilityBridge;
    }

    PaperRewardSink rewardSink() {
        return rewardSink;
    }
}
