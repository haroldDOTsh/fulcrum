package sh.harold.fulcrum.api.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builder surface for tabbed menus that render category tabs, divider rows, and content panes.
 */
public interface TabbedMenuBuilder {

    TabbedMenuBuilder title(Component title);

    TabbedMenuBuilder title(String title);

    TabbedMenuBuilder contentRows(int rows);

    TabbedMenuBuilder divider(Material material);

    TabbedMenuBuilder divider(MenuItem dividerItem);

    TabbedMenuBuilder emptyItem(MenuItem emptyItem);

    TabbedMenuBuilder tab(Consumer<TabBuilder> tabConsumer);

    TabbedMenuBuilder defaultTab(String tabId);

    TabbedMenuBuilder owner(Plugin plugin);

    TabbedMenuBuilder onTabChange(TabChangeListener listener);

    TabbedMenuBuilder tabScroller(TabScrollerConfig config);

    CompletableFuture<Menu> buildAsync(Player player);

    CompletableFuture<Menu> buildAsync();

    /**
     * Builder for individual tabs.
     */
    interface TabBuilder {
        TabBuilder id(String id);

        TabBuilder name(String name);

        TabBuilder name(Component name);

        TabBuilder icon(Material material);

        TabBuilder icon(MenuItem menuItem);

        TabBuilder lore(String... lines);

        TabBuilder lore(Component... lines);

        TabBuilder items(Collection<? extends MenuItem> items);

        TabBuilder items(MenuItem... items);

        TabBuilder items(Supplier<Collection<? extends MenuItem>> supplier);

        TabBuilder emptyMessage(Component message);

        TabBuilder emptyMessage(String message);

        TabBuilder emptyItem(MenuItem item);

        TabBuilder divider(Material material);

        TabBuilder divider(MenuItem dividerItem);

        TabBuilder cooldown(Duration duration);
    }

    @FunctionalInterface
    interface TabChangeListener {
        void onTabChange(Player player, String oldTab, String newTab);
    }

    record TabScrollerConfig(boolean wrapAround) {
        public static TabScrollerConfig defaults() {
            return new TabScrollerConfig(false);
        }
    }
}
