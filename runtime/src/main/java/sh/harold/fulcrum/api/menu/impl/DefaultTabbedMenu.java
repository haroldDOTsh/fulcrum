package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.TabbedMenuBuilder;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.*;

/**
 * Concrete menu implementation that renders tabbed content panes.
 */
public final class DefaultTabbedMenu extends AbstractMenu {

    private static final int TAB_ROW = 0;
    private static final int DIVIDER_ROW = 1;
    private static final int CONTENT_START_SLOT = 18;

    private final Component baseTitle;
    private final List<DefaultTabbedMenuBuilder.TabDefinition> tabs;
    private final MenuItem defaultDividerItem;
    private final MenuItem defaultEmptyItem;
    private final TabbedMenuBuilder.TabChangeListener tabChangeListener;
    private final TabbedMenuBuilder.TabScrollerConfig scrollerConfig;
    private final boolean useScroller;
    private final int visibleTabSlots;
    private final int contentRows;

    private final Map<String, Integer> tabIndex = new HashMap<>();

    private int activeTabIndex;
    private int tabWindowStart;

    DefaultTabbedMenu(String id,
                      Component baseTitle,
                      int contentRows,
                      Plugin ownerPlugin,
                      Player viewer,
                      List<DefaultTabbedMenuBuilder.TabDefinition> tabs,
                      int defaultTabIndex,
                      MenuItem dividerItem,
                      MenuItem emptyItem,
                      TabbedMenuBuilder.TabChangeListener tabChangeListener,
                      TabbedMenuBuilder.TabScrollerConfig scrollerConfig) {
        super(id, composeTitle(baseTitle, tabs.get(defaultTabIndex)), (contentRows + 2) * 9, ownerPlugin, Objects.requireNonNull(viewer, "Viewer cannot be null"));
        this.baseTitle = baseTitle;
        this.tabs = tabs;
        this.activeTabIndex = defaultTabIndex;
        this.defaultDividerItem = dividerItem;
        this.defaultEmptyItem = emptyItem;
        this.tabChangeListener = tabChangeListener;
        this.scrollerConfig = scrollerConfig == null ? TabbedMenuBuilder.TabScrollerConfig.defaults() : scrollerConfig;
        this.contentRows = contentRows;
        this.useScroller = tabs.size() > 9;
        this.visibleTabSlots = useScroller ? 7 : 9;
        this.tabWindowStart = Math.max(0, Math.min(defaultTabIndex, Math.max(0, tabs.size() - visibleTabSlots)));
        for (int i = 0; i < tabs.size(); i++) {
            tabIndex.put(tabs.get(i).id(), i);
        }
        updateContextForActiveTab();
    }

    private static Component composeTitle(Component base, DefaultTabbedMenuBuilder.TabDefinition tab) {
        return base.append(Component.text(" ➜ ", NamedTextColor.DARK_GRAY)).append(tab.name());
    }

    @Override
    public Component getTitle() {
        return composeTitle(baseTitle, tabs.get(activeTabIndex));
    }

    @Override
    public boolean isListMenu() {
        return false;
    }

    @Override
    public boolean isCustomMenu() {
        return true;
    }

    @Override
    protected void renderItems() {
        items.clear();
        renderTabRow();
        renderDividerRow();
        renderContent();
    }

    private void renderTabRow() {
        // Clear row
        for (int slot = TAB_ROW * 9; slot < (TAB_ROW + 1) * 9; slot++) {
            removeItem(slot);
        }

        if (useScroller) {
            renderScrollButtons();
        }

        int slotOffset = useScroller ? 1 : 0;
        int maxDisplay = Math.min(visibleTabSlots, tabs.size());
        ensureWindowVisible();

        for (int i = 0; i < maxDisplay; i++) {
            int tabIndex = tabWindowStart + i;
            DefaultTabbedMenuBuilder.TabDefinition tab = tabs.get(tabIndex);
            boolean active = tabIndex == activeTabIndex;
            MenuButton button = buildTabButton(tab, slotOffset + i, active);
            setButton(button, slotOffset + i);
        }
    }

    private void renderScrollButtons() {
        boolean canScrollLeft = tabWindowStart > 0 || scrollerConfig.wrapAround();
        boolean canScrollRight = (tabWindowStart + visibleTabSlots) < tabs.size() || scrollerConfig.wrapAround();

        if (canScrollLeft) {
            MenuButton left = MenuButton.builder(Material.ARROW)
                    .name(Component.text("<-", NamedTextColor.GRAY))
                    .slot(0)
                    .anchor(true)
                    .sound(Sound.UI_BUTTON_CLICK)
                    .onClick(player -> scrollTabs(-1))
                    .build();
            setButton(left, 0);
        } else {
            MenuItem filler = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text(" "))
                    .build();
            setItem(filler, 0);
        }

        if (canScrollRight) {
            MenuButton right = MenuButton.builder(Material.ARROW)
                    .name(Component.text("->", NamedTextColor.GRAY))
                    .slot(8)
                    .anchor(true)
                    .sound(Sound.UI_BUTTON_CLICK)
                    .onClick(player -> scrollTabs(1))
                    .build();
            setButton(right, 8);
        } else {
            MenuItem filler = MenuDisplayItem.builder(Material.GRAY_STAINED_GLASS_PANE)
                    .name(Component.text(" "))
                    .build();
            setItem(filler, 8);
        }
    }

    private void renderDividerRow() {
        int start = DIVIDER_ROW * 9;
        int end = start + 9;
        MenuItem divider = Optional.ofNullable(tabs.get(activeTabIndex).dividerItem()).orElse(defaultDividerItem);
        for (int slot = start; slot < end; slot++) {
            setItem(divider, slot);
        }
    }

    private void renderContent() {
        int start = CONTENT_START_SLOT;
        int end = CONTENT_START_SLOT + (contentRows * 9) - 1;
        for (int slot = start; slot <= end; slot++) {
            removeItem(slot);
        }

        DefaultTabbedMenuBuilder.TabDefinition tab = tabs.get(activeTabIndex);
        List<MenuItem> anchored = new ArrayList<>();
        List<MenuItem> flowing = new ArrayList<>();

        tab.staticItems().forEach(item -> categorizeItem(item, anchored, flowing));
        tab.resolveDynamicItems().forEach(item -> categorizeItem(item, anchored, flowing));

        Set<Integer> occupied = new HashSet<>();
        for (MenuItem item : anchored) {
            int targetSlot = item.hasSlot() ? item.getSlot() : findNextSlot(start, end, occupied);
            if (targetSlot == -1 || targetSlot < 0 || targetSlot >= size) {
                continue;
            }
            setItem(item, targetSlot);
            if (targetSlot >= start && targetSlot <= end) {
                occupied.add(targetSlot);
            }
        }

        for (MenuItem item : flowing) {
            int slot = findNextSlot(start, end, occupied);
            if (slot == -1) {
                break;
            }
            setItem(item, slot);
            occupied.add(slot);
        }

        boolean hasContent = occupied.stream().anyMatch(slot -> slot >= start && slot <= end);
        if (!hasContent) {
            MenuItem placeholder = resolveEmptyItem(tab);
            int centerSlot = start + ((end - start) / 2);
            setItem(placeholder, centerSlot);
        }
    }

    private void categorizeItem(MenuItem item, List<MenuItem> anchored, List<MenuItem> flowing) {
        if (item == null) {
            return;
        }
        if (item.hasSlot()) {
            anchored.add(item);
        } else {
            flowing.add(item);
        }
    }

    private int findNextSlot(int start, int end, Set<Integer> occupied) {
        for (int slot = start; slot <= end; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private MenuItem resolveEmptyItem(DefaultTabbedMenuBuilder.TabDefinition tab) {
        if (tab.emptyItem() != null) {
            return tab.emptyItem();
        }
        if (tab.emptyMessage() != null) {
            return MenuDisplayItem.builder(Material.BARRIER)
                    .name(tab.emptyMessage())
                    .build();
        }
        return defaultEmptyItem;
    }

    private MenuButton buildTabButton(DefaultTabbedMenuBuilder.TabDefinition tab, int slot, boolean active) {
        MenuButton.Builder builder = MenuButton.builder(tab.iconMaterial())
                .name(active ? Component.text("» ", NamedTextColor.GOLD).append(tab.name()) : tab.name())
                .slot(slot)
                .anchor(true)
                .sound(Sound.UI_BUTTON_CLICK)
                .onClick(player -> selectTab(tab.id(), player));

        if (tab.cooldown() != null) {
            builder.cooldown(tab.cooldown());
        }
        if (!tab.lore().isEmpty()) {
            tab.lore().forEach(builder::lore);
        }
        if (active) {
            builder.secondary("&aActive");
        } else {
            builder.secondary("&7Click to view");
        }
        return builder.build();
    }

    private void selectTab(String tabId, Player player) {
        Integer index = tabIndex.get(tabId);
        if (index == null || index == activeTabIndex) {
            return;
        }
        String oldTab = tabs.get(activeTabIndex).id();
        activeTabIndex = index;
        ensureWindowForActive();
        updateContextForActiveTab();
        refreshInventoryTitle();
        if (tabChangeListener != null) {
            tabChangeListener.onTabChange(player, oldTab, tabId);
        }
    }

    private void scrollTabs(int delta) {
        int maxStart = Math.max(0, tabs.size() - visibleTabSlots);
        int newStart = tabWindowStart + delta;
        if (newStart < 0) {
            newStart = scrollerConfig.wrapAround() ? maxStart : 0;
        } else if (newStart > maxStart) {
            newStart = scrollerConfig.wrapAround() ? 0 : maxStart;
        }
        if (newStart == tabWindowStart) {
            return;
        }
        tabWindowStart = newStart;
        update();
    }

    private void ensureWindowVisible() {
        int maxStart = Math.max(0, tabs.size() - visibleTabSlots);
        tabWindowStart = Math.max(0, Math.min(tabWindowStart, maxStart));
    }

    private void ensureWindowForActive() {
        if (!useScroller) {
            return;
        }
        if (activeTabIndex < tabWindowStart) {
            tabWindowStart = activeTabIndex;
        } else if (activeTabIndex >= tabWindowStart + visibleTabSlots) {
            tabWindowStart = activeTabIndex - visibleTabSlots + 1;
        }
        ensureWindowVisible();
    }

    private void refreshInventoryTitle() {
        Component newTitle = composeTitle(baseTitle, tabs.get(activeTabIndex));
        MenuInventoryHolder holder = new MenuInventoryHolder(this);
        this.inventory = Bukkit.createInventory(holder, size, newTitle);
        if (viewer != null) {
            viewer.openInventory(this.inventory);
        }
        renderItems();
    }

    private void updateContextForActiveTab() {
        DefaultTabbedMenuBuilder.TabDefinition tab = tabs.get(activeTabIndex);
        context.setProperty("activeTabId", tab.id());
        context.setProperty("activeTabName", tab.name());
    }
}
