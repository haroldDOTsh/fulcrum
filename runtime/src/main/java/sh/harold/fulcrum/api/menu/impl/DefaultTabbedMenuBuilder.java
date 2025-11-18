package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.Menu;
import sh.harold.fulcrum.api.menu.TabbedMenuBuilder;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link TabbedMenuBuilder}.
 */
public final class DefaultTabbedMenuBuilder implements TabbedMenuBuilder {

    private static final Component DEFAULT_EMPTY_NAME = Component.text("Empty!", NamedTextColor.RED);
    private static final MenuItem DEFAULT_DIVIDER = MenuDisplayItem.builder(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
            .name(Component.text(" "))
            .build();
    private static final MenuItem DEFAULT_EMPTY_ITEM = MenuDisplayItem.builder(Material.BARRIER)
            .name(DEFAULT_EMPTY_NAME)
            .build();

    private final DefaultMenuService menuService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();
    private final List<TabDefinition> tabs = new ArrayList<>();

    private Component title = Component.text("Tabbed Menu");
    private int contentRows = 3;
    private MenuItem dividerItem = DEFAULT_DIVIDER;
    private MenuItem emptyItem = DEFAULT_EMPTY_ITEM;
    private String defaultTabId;
    private Plugin owner;
    private TabChangeListener tabChangeListener;
    private TabScrollerConfig scrollerConfig = TabScrollerConfig.defaults();

    public DefaultTabbedMenuBuilder(DefaultMenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "MenuService cannot be null");
        this.owner = menuService.getPlugin();
    }

    @Override
    public TabbedMenuBuilder title(Component title) {
        this.title = Objects.requireNonNull(title, "Title cannot be null");
        return this;
    }

    @Override
    public TabbedMenuBuilder title(String title) {
        this.title = deserialize(title);
        return this;
    }

    @Override
    public TabbedMenuBuilder contentRows(int rows) {
        if (rows < 1 || rows > 4) {
            throw new IllegalArgumentException("contentRows must be between 1 and 4");
        }
        this.contentRows = rows;
        return this;
    }

    @Override
    public TabbedMenuBuilder divider(Material material) {
        this.dividerItem = createDividerItem(material);
        return this;
    }

    @Override
    public TabbedMenuBuilder divider(MenuItem dividerItem) {
        this.dividerItem = Objects.requireNonNull(dividerItem, "Divider item cannot be null");
        return this;
    }

    @Override
    public TabbedMenuBuilder emptyItem(MenuItem emptyItem) {
        this.emptyItem = Objects.requireNonNull(emptyItem, "Empty item cannot be null");
        return this;
    }

    @Override
    public TabbedMenuBuilder tab(Consumer<TabBuilder> tabConsumer) {
        Objects.requireNonNull(tabConsumer, "Tab consumer cannot be null");
        DefaultTabBuilder builder = new DefaultTabBuilder();
        tabConsumer.accept(builder);
        tabs.add(builder.build());
        return this;
    }

    @Override
    public TabbedMenuBuilder defaultTab(String tabId) {
        this.defaultTabId = Objects.requireNonNull(tabId, "Tab id cannot be null");
        return this;
    }

    @Override
    public TabbedMenuBuilder owner(Plugin plugin) {
        this.owner = Objects.requireNonNull(plugin, "Owner plugin cannot be null");
        return this;
    }

    @Override
    public TabbedMenuBuilder onTabChange(TabChangeListener listener) {
        this.tabChangeListener = listener;
        return this;
    }

    @Override
    public TabbedMenuBuilder tabScroller(TabScrollerConfig config) {
        this.scrollerConfig = Objects.requireNonNull(config, "Tab scroller config cannot be null");
        return this;
    }

    @Override
    public CompletableFuture<Menu> buildAsync(Player player) {
        Objects.requireNonNull(player, "Player cannot be null");
        return buildMenu(player).thenCompose(menu ->
                menuService.openMenu(menu, player).thenApply(v -> menu)
        );
    }

    @Override
    public CompletableFuture<Menu> buildAsync() {
        throw new UnsupportedOperationException("Tabbed menus require a player context");
    }

    private CompletableFuture<Menu> buildMenu(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            if (tabs.isEmpty()) {
                throw new IllegalStateException("Tabbed menus require at least one tab");
            }

            int resolvedRows = Math.max(1, Math.min(4, contentRows));
            List<TabDefinition> builtTabs = Collections.unmodifiableList(new ArrayList<>(tabs));
            int defaultIndex = resolveDefaultTabIndex(builtTabs);
            String menuId = "tabbed-menu-" + UUID.randomUUID();

            DefaultTabbedMenu menu = new DefaultTabbedMenu(
                    menuId,
                    title,
                    resolvedRows,
                    owner,
                    player,
                    builtTabs,
                    defaultIndex,
                    dividerItem,
                    emptyItem,
                    tabChangeListener,
                    scrollerConfig
            );
            menu.renderItems();
            return menu;
        });
    }

    private int resolveDefaultTabIndex(List<TabDefinition> builtTabs) {
        if (builtTabs.isEmpty()) {
            return 0;
        }
        if (defaultTabId == null) {
            return 0;
        }
        for (int i = 0; i < builtTabs.size(); i++) {
            if (builtTabs.get(i).id().equalsIgnoreCase(defaultTabId)) {
                return i;
            }
        }
        return 0;
    }

    private MenuItem createDividerItem(Material material) {
        return MenuDisplayItem.builder(Objects.requireNonNull(material, "Divider material cannot be null"))
                .name(Component.text(" "))
                .build();
    }

    private Component deserialize(String input) {
        Objects.requireNonNull(input, "Input cannot be null");
        if (input.contains("§") || input.contains("&")) {
            return legacySerializer.deserialize(input);
        }
        return miniMessage.deserialize(input);
    }

    record TabDefinition(String id, Component name, Material iconMaterial, List<Component> lore,
                         List<MenuItem> staticItems, Supplier<Collection<? extends MenuItem>> dynamicSupplier,
                         Component emptyMessage, MenuItem dividerItem, MenuItem emptyItem, Duration cooldown) {

        Collection<MenuItem> resolveDynamicItems() {
                if (dynamicSupplier == null) {
                    return Collections.emptyList();
                }
                Collection<? extends MenuItem> supplied = dynamicSupplier.get();
                if (supplied == null) {
                    return Collections.emptyList();
                }
                return supplied.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }
        }

    private final class DefaultTabBuilder implements TabBuilder {
        private final List<Component> lore = new ArrayList<>();
        private final List<MenuItem> staticItems = new ArrayList<>();
        private String id;
        private Component name = Component.text("Tab");
        private Material iconMaterial = Material.BOOK;
        private Supplier<Collection<? extends MenuItem>> dynamicSupplier;
        private Component emptyMessage;
        private MenuItem divider;
        private MenuItem empty;
        private Duration cooldown;

        @Override
        public TabBuilder id(String id) {
            this.id = Objects.requireNonNull(id, "Tab id cannot be null");
            return this;
        }

        @Override
        public TabBuilder name(String name) {
            this.name = deserialize(name);
            return this;
        }

        @Override
        public TabBuilder name(Component name) {
            this.name = Objects.requireNonNull(name, "Tab name cannot be null");
            return this;
        }

        @Override
        public TabBuilder icon(Material material) {
            this.iconMaterial = Objects.requireNonNull(material, "Tab icon cannot be null");
            return this;
        }

        @Override
        public TabBuilder icon(MenuItem menuItem) {
            Objects.requireNonNull(menuItem, "Menu item cannot be null");
            this.iconMaterial = menuItem.getDisplayItem().getType();
            return this;
        }

        @Override
        public TabBuilder lore(String... lines) {
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && !line.isEmpty()) {
                        lore.add(deserialize(line));
                    }
                }
            }
            return this;
        }

        @Override
        public TabBuilder lore(Component... lines) {
            if (lines != null) {
                for (Component line : lines) {
                    if (line != null) {
                        lore.add(line);
                    }
                }
            }
            return this;
        }

        @Override
        public TabBuilder items(Collection<? extends MenuItem> items) {
            Objects.requireNonNull(items, "Items cannot be null");
            items.forEach(item -> staticItems.add(Objects.requireNonNull(item, "Menu item cannot be null")));
            return this;
        }

        @Override
        public TabBuilder items(MenuItem... items) {
            if (items != null) {
                for (MenuItem item : items) {
                    staticItems.add(Objects.requireNonNull(item, "Menu item cannot be null"));
                }
            }
            return this;
        }

        @Override
        public TabBuilder items(Supplier<Collection<? extends MenuItem>> supplier) {
            this.dynamicSupplier = Objects.requireNonNull(supplier, "Supplier cannot be null");
            return this;
        }

        @Override
        public TabBuilder emptyMessage(Component message) {
            this.emptyMessage = message;
            return this;
        }

        @Override
        public TabBuilder emptyMessage(String message) {
            if (message != null) {
                this.emptyMessage = deserialize(message);
            }
            return this;
        }

        @Override
        public TabBuilder emptyItem(MenuItem item) {
            this.empty = Objects.requireNonNull(item, "Empty item cannot be null");
            return this;
        }

        @Override
        public TabBuilder divider(Material material) {
            this.divider = createDividerItem(material);
            return this;
        }

        @Override
        public TabBuilder divider(MenuItem dividerItem) {
            this.divider = Objects.requireNonNull(dividerItem, "Divider item cannot be null");
            return this;
        }

        @Override
        public TabBuilder cooldown(Duration duration) {
            this.cooldown = duration;
            return this;
        }

        TabDefinition build() {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Tab id must be specified");
            }
            return new TabDefinition(
                    id,
                    name,
                    iconMaterial,
                    List.copyOf(lore),
                    List.copyOf(staticItems),
                    dynamicSupplier,
                    emptyMessage,
                    divider,
                    empty,
                    cooldown
            );
        }
    }
}
