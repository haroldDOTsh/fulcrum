package sh.harold.fulcrum.velocity.fundamentals.motd;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import sh.harold.fulcrum.api.network.NetworkConfigService;
import sh.harold.fulcrum.velocity.FulcrumVelocityPlugin;
import sh.harold.fulcrum.velocity.lifecycle.ServiceLocator;
import sh.harold.fulcrum.velocity.lifecycle.VelocityFeature;

import java.util.List;

public final class VelocityMotdFeature implements VelocityFeature {

    private final CenteredTextFormatter formatter = new CenteredTextFormatter();
    private ProxyServer proxyServer;
    private Logger logger;
    private NetworkConfigService networkConfigService;

    @Override
    public String getName() {
        return "Motd";
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public void initialize(ServiceLocator serviceLocator, Logger logger) {
        this.proxyServer = serviceLocator.getRequiredService(ProxyServer.class);
        this.networkConfigService = serviceLocator.getService(NetworkConfigService.class).orElse(null);
        this.logger = logger;
        FulcrumVelocityPlugin plugin = serviceLocator.getRequiredService(FulcrumVelocityPlugin.class);
        this.proxyServer.getEventManager().register(plugin, this);
    }

    @Override
    public void shutdown() {
        // Nothing to cleanup
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (networkConfigService == null) {
            return;
        }

        List<String> lines = networkConfigService.getActiveProfile().getStringList("motd");
        if (lines.isEmpty()) {
            return;
        }

        String firstLine = formatter.center(lines.get(0));
        String secondLine = lines.size() > 1 ? formatter.center(lines.get(1)) : "";

        Component motd = LegacyComponentSerializer.legacyAmpersand().deserialize(firstLine + "\n" + secondLine);
        event.setPing(event.getPing().asBuilder()
                .description(motd)
                .build());
    }
}
