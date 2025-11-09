package sh.harold.fulcrum.npc;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.fulcrum.api.menu.MenuService;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;
import sh.harold.fulcrum.lifecycle.ServiceLocatorImpl;
import sh.harold.fulcrum.npc.adapter.CitizensNpcAdapter;
import sh.harold.fulcrum.npc.adapter.NpcAdapter;
import sh.harold.fulcrum.npc.behavior.DefaultNpcInteractionHelpers;
import sh.harold.fulcrum.npc.orchestration.PoiNpcOrchestrator;
import sh.harold.fulcrum.npc.poi.PoiActivationBus;
import sh.harold.fulcrum.npc.poi.PoiDescriptorRegistry;
import sh.harold.fulcrum.npc.skin.HttpNpcSkinCacheService;
import sh.harold.fulcrum.npc.skin.NpcSkinCacheService;
import sh.harold.fulcrum.npc.view.NpcViewerService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Bootstraps the stateless NPC toolkit.
 */
public final class NpcFeature implements PluginFeature {
    private JavaPlugin plugin;
    private NpcRegistry npcRegistry;
    private PoiDescriptorRegistry descriptorRegistry;
    private PoiNpcOrchestrator orchestrator;
    private NpcViewerService viewerService;
    private ExecutorService skinExecutor;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        this.plugin = plugin;
        Logger logger = plugin.getLogger();

        this.npcRegistry = new NpcRegistry();
        this.descriptorRegistry = new PoiDescriptorRegistry();
        container.register(NpcRegistry.class, npcRegistry);
        container.register(PoiDescriptorRegistry.class, descriptorRegistry);
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.registerService(NpcRegistry.class, npcRegistry);
            locator.registerService(PoiDescriptorRegistry.class, descriptorRegistry);
        }

        PoiActivationBus activationBus = container.getOptional(PoiActivationBus.class)
                .orElseGet(() -> locator != null
                        ? locator.findService(PoiActivationBus.class).orElse(null)
                        : null);

        if (activationBus == null) {
            logger.warning("POI activation bus unavailable; NPC orchestrator disabled.");
            return;
        }

        if (!isCitizensPresent()) {
            logger.warning("Citizens plugin not detected; NPC toolkit will not spawn entities.");
            return;
        }

        this.skinExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                new NamedThreadFactory("npc-skin"));
        NpcSkinCacheService skinCache = new HttpNpcSkinCacheService(
                logger,
                skinExecutor,
                plugin.getDataFolder().toPath().resolve("cache").resolve("npc-skins")
        );
        NpcAdapter adapter = new CitizensNpcAdapter(plugin);
        this.viewerService = new NpcViewerService(plugin,
                resolveService(container, sh.harold.fulcrum.api.rank.RankService.class),
                resolveService(container, sh.harold.fulcrum.fundamentals.session.PlayerSessionService.class));
        this.orchestrator = new PoiNpcOrchestrator(
                plugin,
                logger,
                npcRegistry,
                descriptorRegistry,
                activationBus,
                adapter,
                skinCache,
                viewerService,
                new DefaultNpcInteractionHelpers(
                        resolveService(container, sh.harold.fulcrum.api.rank.RankService.class),
                        resolveService(container, MenuService.class),
                        logger)
        );
        plugin.getServer().getPluginManager().registerEvents(new sh.harold.fulcrum.npc.orchestration.NpcInteractionListener(orchestrator), plugin);

        logger.info("NPC toolkit initialized (definitions=" + npcRegistry.size() + ")");
    }

    @Override
    public void shutdown() {
        if (orchestrator != null) {
            orchestrator.close();
            orchestrator = null;
        }
        if (skinExecutor != null) {
            skinExecutor.shutdownNow();
            skinExecutor = null;
        }
        if (viewerService != null) {
            viewerService.close();
            viewerService = null;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            locator.unregisterService(NpcRegistry.class);
            locator.unregisterService(PoiDescriptorRegistry.class);
        }
    }

    @Override
    public int getPriority() {
        return 250;
    }

    private boolean isCitizensPresent() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        return citizens != null && citizens.isEnabled();
    }

    private <T> T resolveService(DependencyContainer container, Class<T> type) {
        T local = container.getOptional(type).orElse(null);
        if (local != null) {
            return local;
        }
        ServiceLocatorImpl locator = ServiceLocatorImpl.getInstance();
        if (locator != null) {
            return locator.findService(type).orElse(null);
        }
        return null;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final String baseName;

        private NamedThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName(baseName + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
