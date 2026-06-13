package sh.harold.fulcrum.fundamentals.creative;

import org.bukkit.plugin.java.JavaPlugin;
import sh.harold.creative.library.menu.MenuService;
import sh.harold.creative.library.menu.core.StandardMenuService;
import sh.harold.creative.library.menu.paper.PaperMenuPlatform;
import sh.harold.creative.library.message.paper.PaperMessageSender;
import sh.harold.creative.library.scoreboard.ScoreboardService;
import sh.harold.creative.library.scoreboard.paper.PaperScoreboardPlatform;
import sh.harold.creative.library.sound.SoundCueService;
import sh.harold.creative.library.sound.paper.PaperSoundCuePlatform;
import sh.harold.fulcrum.lifecycle.DependencyContainer;
import sh.harold.fulcrum.lifecycle.PluginFeature;

public final class CreativeLibraryFeature implements PluginFeature {
    private PaperMenuPlatform menuPlatform;
    private PaperSoundCuePlatform soundPlatform;
    private PaperScoreboardPlatform scoreboardPlatform;

    @Override
    public void initialize(JavaPlugin plugin, DependencyContainer container) {
        PaperMessageSender messages = new PaperMessageSender();
        MenuService menus = new StandardMenuService();
        soundPlatform = new PaperSoundCuePlatform(plugin);
        menuPlatform = new PaperMenuPlatform(plugin, menus, soundPlatform);
        scoreboardPlatform = new PaperScoreboardPlatform(plugin);

        container.register(PaperMessageSender.class, messages);
        container.register(MenuService.class, menus);
        container.register(PaperMenuPlatform.class, menuPlatform);
        container.register(SoundCueService.class, soundPlatform);
        container.register(PaperSoundCuePlatform.class, soundPlatform);
        container.register(ScoreboardService.class, scoreboardPlatform.service());
        container.register(PaperScoreboardPlatform.class, scoreboardPlatform);
    }

    @Override
    public void shutdown() {
        if (scoreboardPlatform != null) {
            scoreboardPlatform.close();
            scoreboardPlatform = null;
        }
        if (menuPlatform != null) {
            menuPlatform.close();
            menuPlatform = null;
        }
        if (soundPlatform != null) {
            soundPlatform.close();
            soundPlatform = null;
        }
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
