package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import sh.harold.fulcrum.api.menu.util.ColorUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a static display item in a menu.
 * Used for decoration or information display without interaction.
 */
public class MenuDisplayItem implements MenuItem {
    
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private ItemStack displayItem;
    private Component name;
    private List<Component> lore;
    private final int slot;
    
    private MenuDisplayItem(Builder builder) {
        this.slot = builder.slot;
        this.name = builder.name;
        this.lore = new ArrayList<>(builder.lore);
        
        // Build the ItemStack
        this.displayItem = new ItemStack(builder.material, builder.amount);
        updateItemMeta();
    }
    
    private void updateItemMeta() {
        ItemMeta meta = displayItem.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(name);
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            displayItem.setItemMeta(meta);
        }
    }
    
    @Override
    public Component getName() {
        return name;
    }
    
    @Override
    public List<Component> getLore() {
        return new ArrayList<>(lore);
    }
    
    @Override
    public ItemStack getDisplayItem() {
        return displayItem.clone();
    }
    
    @Override
    public void setDisplayItem(ItemStack itemStack) {
        this.displayItem = Objects.requireNonNull(itemStack, "ItemStack cannot be null").clone();
        // Preserve name and lore if they exist
        if (name != null || !lore.isEmpty()) {
            updateItemMeta();
        }
    }
    
    @Override
    public int getSlot() {
        return slot;
    }
    
    @Override
    public boolean hasSlot() {
        return slot >= 0;
    }
    
    /**
     * Creates a new builder for MenuDisplayItem.
     * 
     * @param material the material for the display item
     * @return a new Builder instance
     */
    public static Builder builder(Material material) {
        return new Builder(material);
    }
    
    /**
     * Builder class for MenuDisplayItem with fluent API.
     */
    public static class Builder {
        private final MiniMessage miniMessage = MiniMessage.miniMessage();
        private final Material material;
        private int amount = 1;
        private Component name;
        private final List<Component> lore = new ArrayList<>();
        private int slot = -1;
        
        private Builder(Material material) {
            this.material = Objects.requireNonNull(material, "Material cannot be null");
        }
        
        /**
         * Sets the display name of the item.
         * Supports MiniMessage formatting and legacy color codes.
         * Uses dual approach: legacy compatibility with &amp;r prefix and Adventure's proper decoration API.
         *
         * @param name the display name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                // Process text through both legacy and Adventure approaches
                
                // 1. Legacy approach: Add reset code if not present
                String processedName;
                if (name.startsWith("&r") || name.startsWith("<reset>")) {
                    processedName = name;
                } else {
                    processedName = "&r" + name;
                }
                
                // 2. Adventure approach: Use proper decoration API
                Component component;
                if (processedName.contains("§") || processedName.contains("&")) {
                    // Legacy format - use LegacyComponentSerializer
                    component = LegacyComponentSerializer.legacyAmpersand().deserialize(processedName);
                } else {
                    // MiniMessage format
                    component = miniMessage.deserialize(processedName);
                }
                
                // 3. Apply Adventure italic decoration fix
                component = component.decoration(TextDecoration.ITALIC, false);
                
                this.name = component;
            }
            return this;
        }
        
        /**
         * Sets the display name using an Adventure Component.
         * 
         * @param name the display name component
         * @return this builder
         */
        public Builder name(Component name) {
            this.name = name;
            return this;
        }
        
        /**
         * Adds a secondary line in dark gray below the name.
         * 
         * @param text the secondary text
         * @return this builder
         */
        public Builder secondary(String text) {
            if (text != null && !text.isEmpty()) {
                Component secondary = miniMessage.deserialize(ColorUtils.convertLegacyToMiniMessage(text))
                    .color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false);
                lore.add(0, secondary); // Add at beginning
            }
            return this;
        }
        
        /**
         * Adds a description block with automatic word wrapping.
         * Each line will be formatted in gray.
         * 
         * @param description the description text
         * @return this builder
         */
        public Builder description(String description) {
            if (description != null && !description.isEmpty()) {
                // Add empty line before description if lore exists
                if (!lore.isEmpty()) {
                    lore.add(Component.empty());
                }
                
                // Word wrap at approximately 30 characters
                List<String> wrapped = wordWrap(description, 30);
                for (String line : wrapped) {
                    Component descLine = miniMessage.deserialize(ColorUtils.convertLegacyToMiniMessage(line))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false);
                    lore.add(descLine);
                }
            }
            return this;
        }
        
        /**
         * Adds a single lore line.
         * 
         * @param line the lore line
         * @return this builder
         */
        public Builder lore(String line) {
            if (line != null) {
                Component loreLine = miniMessage.deserialize(ColorUtils.convertLegacyToMiniMessage(line))
                    .decoration(TextDecoration.ITALIC, false);
                lore.add(loreLine);
            }
            return this;
        }
        
        /**
         * Adds a lore line using an Adventure Component.
         * 
         * @param line the lore component
         * @return this builder
         */
        public Builder lore(Component line) {
            if (line != null) {
                lore.add(line);
            }
            return this;
        }
        
        /**
         * Adds multiple lore lines.
         * 
         * @param lines the lore lines
         * @return this builder
         */
        public Builder lore(String... lines) {
            for (String line : lines) {
                lore(line);
            }
            return this;
        }
        
        /**
         * Adds multiple lore lines using Adventure Components.
         * 
         * @param lines the lore components
         * @return this builder
         */
        public Builder lore(Component... lines) {
            lore.addAll(Arrays.asList(lines));
            return this;
        }
        
        /**
         * Sets the item amount.
         * 
         * @param amount the stack size
         * @return this builder
         */
        public Builder amount(int amount) {
            this.amount = Math.max(1, Math.min(64, amount));
            return this;
        }
        
        /**
         * Sets the slot position for this item.
         * 
         * @param slot the slot position (0-53 for double chest)
         * @return this builder
         */
        public Builder slot(int slot) {
            this.slot = slot;
            return this;
        }
        
        /**
         * Builds the MenuDisplayItem.
         * 
         * @return the built MenuDisplayItem
         */
        public MenuDisplayItem build() {
            return new MenuDisplayItem(this);
        }
        
        /**
         * Word wraps text at the specified length.
         */
        private List<String> wordWrap(String text, int maxLength) {
            List<String> lines = new ArrayList<>();
            String[] words = text.split(" ");
            StringBuilder currentLine = new StringBuilder();
            
            for (String word : words) {
                if (currentLine.length() > 0 && currentLine.length() + word.length() + 1 > maxLength) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
            
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
            
            return lines;
        }
    }
}