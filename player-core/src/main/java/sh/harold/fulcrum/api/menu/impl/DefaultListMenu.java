package sh.harold.fulcrum.api.menu.impl;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.menu.ListMenuBuilder;
import sh.harold.fulcrum.api.menu.component.MenuButton;
import sh.harold.fulcrum.api.menu.component.MenuDisplayItem;
import sh.harold.fulcrum.api.menu.component.MenuItem;

import java.util.*;

/**
 * Implementation of a paginated list menu.
 * Automatically handles pagination for large item sets.
 */
public class DefaultListMenu extends AbstractMenu {
    
    private final List<MenuItem> contentItems = new ArrayList<>();
    private final Map<Integer, MenuButton> persistentButtons = new HashMap<>();
    private final Set<Integer> borderSlots = new HashSet<>();
    
    private int currentPage = 1;
    private int itemsPerPage;
    private int contentStartSlot = 0;
    private int contentEndSlot;
    
    private MenuButton previousButton;
    private MenuButton nextButton;
    private int previousButtonSlot = -1;
    private int nextButtonSlot = -1;
    private int pageIndicatorSlot = -1;
    
    private Component emptyMessage = Component.text("No items to display", NamedTextColor.GRAY);
    private boolean autoRefresh = false;
    private int refreshInterval = 0;
    
    public DefaultListMenu(String id, Component title, int rows, Plugin ownerPlugin, Player viewer) {
        super(id, title, rows * 9, ownerPlugin, viewer);
        this.contentEndSlot = size - 1;
        calculateItemsPerPage();
    }
    
    @Override
    public boolean isListMenu() {
        return true;
    }
    
    @Override
    public boolean isCustomMenu() {
        return false;
    }
    
    @Override
    public boolean navigateToPage(int page) {
        if (page < 1 || page > getTotalPages()) {
            return false;
        }
        
        int oldPage = currentPage;
        currentPage = page;
        context.setCurrentPage(page);
        
        // Re-render the menu
        update();
        
        // Trigger page change handlers if any
        Object handler = context.getProperty("pageChangeHandler", Object.class).orElse(null);
        if (handler instanceof ListMenuBuilder.PageChangeHandler) {
            ((ListMenuBuilder.PageChangeHandler) handler).onPageChange(viewer, oldPage, page);
        }
        
        return true;
    }
    
    /**
     * Adds content items to be displayed.
     */
    public void addContentItems(Collection<? extends MenuItem> items) {
        contentItems.addAll(items);
        updateTotalPages();
    }
    
    /**
     * Adds a single content item.
     */
    public void addContentItem(MenuItem item) {
        contentItems.add(item);
        updateTotalPages();
    }
    
    /**
     * Clears all content items.
     */
    public void clearContentItems() {
        contentItems.clear();
        updateTotalPages();
    }
    
    /**
     * Sets a persistent button that appears on all pages.
     */
    public void setPersistentButton(MenuButton button, int slot) {
        persistentButtons.put(slot, button);
        super.setButton(button, slot);
    }
    
    /**
     * Sets up navigation buttons.
     */
    public void setNavigationButtons(MenuButton previous, int previousSlot, 
                                   MenuButton next, int nextSlot) {
        this.previousButton = previous;
        this.previousButtonSlot = previousSlot;
        this.nextButton = next;
        this.nextButtonSlot = nextSlot;
    }
    
    /**
     * Sets the page indicator slot.
     */
    public void setPageIndicatorSlot(int slot) {
        this.pageIndicatorSlot = slot;
    }
    
    /**
     * Sets the content slot range.
     */
    public void setContentSlotRange(int startSlot, int endSlot) {
        this.contentStartSlot = startSlot;
        this.contentEndSlot = endSlot;
        calculateItemsPerPage();
    }
    
    /**
     * Adds a border with the specified item.
     */
    public void addBorderItem(MenuItem borderItem) {
        borderSlots.addAll(getBorderSlots());
        for (int slot : borderSlots) {
            super.setItem(borderItem, slot);
        }
        calculateItemsPerPage();
    }
    
    /**
     * Sets the empty message.
     */
    public void setEmptyMessage(Component message) {
        this.emptyMessage = message;
    }
    
    /**
     * Enables auto-refresh.
     */
    public void enableAutoRefresh(int intervalSeconds) {
        this.autoRefresh = true;
        this.refreshInterval = intervalSeconds;
        // TODO: Implement scheduled refresh task
    }
    
    @Override
    protected void renderItems() {
        // Clear inventory first
        inventory.clear();
        
        // FIXED: Instead of clearing ALL items, only clear content slots
        // This preserves persistent buttons, borders, and navigation items
        List<Integer> contentSlots = getAvailableContentSlots();
        for (int slot : contentSlots) {
            items.remove(slot);
        }
        
        // Render persistent buttons and borders first
        persistentButtons.forEach((slot, button) -> super.setButton(button, slot));
        
        // Re-add border items
        for (int slot : borderSlots) {
            MenuItem borderItem = super.getItem(slot);
            if (borderItem != null) {
                super.setItem(borderItem, slot);
            }
        }
        
        // Handle empty content
        if (contentItems.isEmpty()) {
            // Display empty message in center
            int centerSlot = size / 2;
            MenuItem emptyItem = MenuDisplayItem.builder(Material.PAPER)
                .name(emptyMessage)
                .build();
            super.setItem(emptyItem, centerSlot);
            return;
        }
        
        // Calculate page boundaries
        int startIndex = (currentPage - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, contentItems.size());
        
        // Get available content slots
        List<Integer> availableSlots = getAvailableContentSlots();
        
        // Place items in available slots
        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < availableSlots.size(); i++) {
            MenuItem item = contentItems.get(i);
            int slot = availableSlots.get(slotIndex++);
            super.setItem(item, slot);
        }
        
        // Update navigation buttons
        updateNavigationButtons();
        
        // Update page indicator
        updatePageIndicator();
    }
    
    private void calculateItemsPerPage() {
        List<Integer> availableSlots = getAvailableContentSlots();
        this.itemsPerPage = availableSlots.size();
        updateTotalPages();
    }
    
    private List<Integer> getAvailableContentSlots() {
        List<Integer> available = new ArrayList<>();
        
        for (int slot = contentStartSlot; slot <= contentEndSlot && slot < size; slot++) {
            // Skip border slots
            if (borderSlots.contains(slot)) continue;
            
            // Skip persistent button slots
            if (persistentButtons.containsKey(slot)) continue;
            
            // Skip navigation button slots
            if (slot == previousButtonSlot || slot == nextButtonSlot) continue;
            
            // Skip page indicator slot
            if (slot == pageIndicatorSlot) continue;
            
            available.add(slot);
        }
        
        return available;
    }
    
    private void updateTotalPages() {
        if (itemsPerPage > 0 && !contentItems.isEmpty()) {
            int totalPages = (contentItems.size() + itemsPerPage - 1) / itemsPerPage;
            context.setTotalPages(totalPages);
        } else {
            context.setTotalPages(1);
        }
    }
    
    private void updateNavigationButtons() {
        // Previous button
        if (previousButton != null && previousButtonSlot >= 0) {
            if (currentPage > 1) {
                // Create clickable previous button
                MenuButton prevButton = MenuButton.builder(previousButton.getDisplayItem().getType())
                    .name(previousButton.getName())
                    .lore(previousButton.getLore().toArray(new Component[0]))
                    .onClick(player -> navigateToPage(currentPage - 1))
                    .build();
                super.setButton(prevButton, previousButtonSlot);
            } else {
                // Create grayed out previous button
                MenuItem disabledPrev = MenuDisplayItem.builder(Material.GRAY_DYE)
                    .name(Component.text("No Previous Page", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabledPrev, previousButtonSlot);
            }
        }
        
        // Next button
        if (nextButton != null && nextButtonSlot >= 0) {
            if (currentPage < getTotalPages()) {
                // Create clickable next button
                MenuButton nextBtn = MenuButton.builder(nextButton.getDisplayItem().getType())
                    .name(nextButton.getName())
                    .lore(nextButton.getLore().toArray(new Component[0]))
                    .onClick(player -> navigateToPage(currentPage + 1))
                    .build();
                super.setButton(nextBtn, nextButtonSlot);
            } else {
                // Create grayed out next button
                MenuItem disabledNext = MenuDisplayItem.builder(Material.GRAY_DYE)
                    .name(Component.text("No Next Page", NamedTextColor.GRAY))
                    .build();
                super.setItem(disabledNext, nextButtonSlot);
            }
        }
    }
    
    private void updatePageIndicator() {
        if (pageIndicatorSlot >= 0) {
            Component indicatorText = Component.text("Page ", NamedTextColor.YELLOW)
                .append(Component.text(currentPage, NamedTextColor.WHITE))
                .append(Component.text(" of ", NamedTextColor.YELLOW))
                .append(Component.text(getTotalPages(), NamedTextColor.WHITE));
            
            MenuItem indicator = MenuDisplayItem.builder(Material.BOOK)
                .name(indicatorText)
                .lore(Component.text(contentItems.size() + " total items", NamedTextColor.GRAY))
                .build();
            
            super.setItem(indicator, pageIndicatorSlot);
        }
    }
}