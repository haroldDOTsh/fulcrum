package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

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
         * Automatically adds &r prefix to prevent italicization.
         *
         * @param name the display name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                // Automatically prepend &r to prevent italicization unless already present
                String processedName = name.startsWith("&r") ? name : "&r" + name;
                // Convert legacy color codes to MiniMessage format
                String converted = convertLegacyColors(processedName);
                this.name = miniMessage.deserialize(converted);
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
                Component secondary = miniMessage.deserialize(convertLegacyColors(text))
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
                    Component descLine = miniMessage.deserialize(convertLegacyColors(line))
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
                Component loreLine = miniMessage.deserialize(convertLegacyColors(line))
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
         * Converts legacy color codes (&) to MiniMessage format.
         */
        private String convertLegacyColors(String text) {
            if (text == null) return null;
            
            // Replace color codes
            text = text.replace("&0", "<black>");
            text = text.replace("&1", "<dark_blue>");
            text = text.replace("&2", "<dark_green>");
            text = text.replace("&3", "<dark_aqua>");
            text = text.replace("&4", "<dark_red>");
            text = text.replace("&5", "<dark_purple>");
            text = text.replace("&6", "<gold>");
            text = text.replace("&7", "<gray>");
            text = text.replace("&8", "<dark_gray>");
            text = text.replace("&9", "<blue>");
            text = text.replace("&a", "<green>");
            text = text.replace("&b", "<aqua>");
            text = text.replace("&c", "<red>");
            text = text.replace("&d", "<light_purple>");
            text = text.replace("&e", "<yellow>");
            text = text.replace("&f", "<white>");
            
            // Replace formatting codes
            text = text.replace("&k", "<obfuscated>");
            text = text.replace("&l", "<bold>");
            text = text.replace("&m", "<strikethrough>");
            text = text.replace("&n", "<underlined>");
            text = text.replace("&o", "<italic>");
            text = text.replace("&r", "<reset>");
            
            return text;
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