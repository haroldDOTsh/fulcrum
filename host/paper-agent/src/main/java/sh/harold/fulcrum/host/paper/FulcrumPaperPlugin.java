package sh.harold.fulcrum.host.paper;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.host.api.HostMenuContribution;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class FulcrumPaperPlugin extends JavaPlugin {
    private PaperHostMainThread mainThread;
    private PaperPlayerSessionListener sessionListener;
    private PaperObservationSink observationSink;
    private PaperCapabilityBridge capabilityBridge;
    private PaperRewardSink rewardSink;
    private PaperHostMenuRuntime menuRuntime;
    private List<PaperLoadedContribution<HostMenuContribution>> loadedMenuContributions = List.of();

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
        List<HostMenuContribution> menuContributions = new ArrayList<>(ServiceLoader
                .load(HostMenuContribution.class, FulcrumPaperPlugin.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(contribution -> !contribution.commandAliases().isEmpty())
                .toList());
        loadedMenuContributions = configuration.contributionBundleDirectory()
                .map(directory -> new PaperContributionBundleCatalog(
                        directory,
                        configuration.securityContext().identity().instanceId().value()))
                .map(PaperContributionBundleCatalog::loadMenuContributions)
                .orElseGet(List::of);
        loadedMenuContributions.stream()
                .map(PaperLoadedContribution::provider)
                .filter(contribution -> !contribution.commandAliases().isEmpty())
                .forEach(menuContributions::add);
        if (!menuContributions.isEmpty()) {
            menuRuntime = new PaperHostMenuRuntime(
                    this,
                    menuContributions,
                    configuration.sessionId().value(),
                    Clock.systemUTC());
            menuRuntime.registerWithServer();
        }
    }

    @Override
    public void onDisable() {
        if (menuRuntime != null) {
            menuRuntime.close();
            menuRuntime = null;
        }
        for (PaperLoadedContribution<HostMenuContribution> contribution : loadedMenuContributions) {
            try {
                contribution.close();
            } catch (java.io.IOException exception) {
                getLogger().warning("could not close Paper menu contribution bundle: " + exception.getMessage());
            }
        }
        loadedMenuContributions = List.of();
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

    PaperHostMenuRuntime menuRuntime() {
        return menuRuntime;
    }
}
