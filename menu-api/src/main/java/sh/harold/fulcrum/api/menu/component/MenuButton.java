package sh.harold.fulcrum.api.menu.component;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Represents an interactive button in a menu with click handlers and cooldown support.
 */
public class MenuButton implements MenuItem {
    
    // Predefined utility buttons
    public static final MenuButton CLOSE = builder(Material.BARRIER)
        .name("<red>Close")
        .description("Click to close this menu")
        .onClick(player -> player.closeInventory())
        .build();
    
    public static final MenuButton BACK = builder(Material.ARROW)
        .name("<yellow>Back")
        .description("Return to the previous menu")
        .onClick(player -> {
            // This will be handled by the menu system
            player.sendMessage(Component.text("Back functionality should be implemented by menu system", NamedTextColor.GRAY));
        })
        .build();
    
    public static final MenuButton NEXT_PAGE = builder(Material.LIME_DYE)
        .name("<green>Next Page")
        .secondary("Go to the next page")
        .onClick(player -> {
            // This will be handled by the paginated menu
            player.sendMessage(Component.text("Next page functionality should be implemented by menu system", NamedTextColor.GRAY));
        })
        .build();
    
    public static final MenuButton PREVIOUS_PAGE = builder(Material.RED_DYE)
        .name("<red>Previous Page")
        .secondary("Go to the previous page")
        .onClick(player -> {
            // This will be handled by the paginated menu
            player.sendMessage(Component.text("Previous page functionality should be implemented by menu system", NamedTextColor.GRAY));
        })
        .build();
    
    public static final MenuButton REFRESH = builder(Material.SUNFLOWER)
        .name("<aqua>Refresh")
        .description("Click to refresh this menu")
        .onClick(player -> {
            // This will be handled by the menu system
            player.sendMessage(Component.text("Refresh functionality should be implemented by menu system", NamedTextColor.GRAY));
        })
        .build();
    
    public static final MenuButton SEARCH = builder(Material.COMPASS)
        .name("<light_purple>Search")
        .description("Click to search for items")
        .onClick(player -> {
            // This will be handled by the menu system
            player.sendMessage(Component.text("Search functionality should be implemented by menu system", NamedTextColor.GRAY));
        })
        .build();
    
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private ItemStack displayItem;
    private Component name;
    private List<Component> lore;
    private final int slot;
    private final Map<ClickType, Consumer<Player>> clickHandlers;
    private final Duration cooldown;
    private final Map<UUID, Instant> cooldowns = new ConcurrentHashMap<>();
    
    private MenuButton(Builder builder) {
        this.slot = builder.slot;
        this.name = builder.name;
        this.lore = new ArrayList<>(builder.lore);
        this.clickHandlers = new HashMap<>(builder.clickHandlers);
        this.cooldown = builder.cooldown;
        
        // Add click prompt if there are handlers and it's not already there
        if (!clickHandlers.isEmpty() && !builder.skipClickPrompt) {
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(Component.text("Click to interact!", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }
        
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
    
    /**
     * Handles a click event on this button.
     * 
     * @param player the player who clicked
     * @param clickType the type of click
     * @return true if the click was handled, false otherwise
     */
    public boolean handleClick(Player player, ClickType clickType) {
        // Check cooldown
        if (cooldown != null && !cooldown.isZero()) {
            UUID playerId = player.getUniqueId();
            Instant lastClick = cooldowns.get(playerId);
            Instant now = Instant.now();
            
            if (lastClick != null && Duration.between(lastClick, now).compareTo(cooldown) < 0) {
                Duration remaining = cooldown.minus(Duration.between(lastClick, now));
                player.sendMessage(Component.text("Please wait " + formatDuration(remaining) + " before clicking again!", 
                    NamedTextColor.RED));
                return false;
            }
            
            cooldowns.put(playerId, now);
        }
        
        // Try specific click type first
        Consumer<Player> handler = clickHandlers.get(clickType);
        if (handler == null) {
            // Fall back to generic handler
            handler = clickHandlers.get(null);
        }
        
        if (handler != null) {
            handler.accept(player);
            return true;
        }
        
        return false;
    }
    
    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds + "s";
        } else {
            long minutes = seconds / 60;
            seconds = seconds % 60;
            return minutes + "m " + seconds + "s";
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
     * Creates a new builder for MenuButton.
     * 
     * @param material the material for the button
     * @return a new Builder instance
     */
    public static Builder builder(Material material) {
        return new Builder(material);
    }
    
    /**
     * Builder class for MenuButton with fluent API.
     */
    public static class Builder {
        private final MiniMessage miniMessage = MiniMessage.miniMessage();
        private final Material material;
        private int amount = 1;
        private Component name;
        private final List<Component> lore = new ArrayList<>();
        private int slot = -1;
        private final Map<ClickType, Consumer<Player>> clickHandlers = new HashMap<>();
        private Duration cooldown;
        private boolean skipClickPrompt = false;
        
        private Builder(Material material) {
            this.material = Objects.requireNonNull(material, "Material cannot be null");
        }
        
        /**
         * Sets the display name of the button.
         * Supports MiniMessage formatting and legacy color codes.
         * 
         * @param name the display name
         * @return this builder
         */
        public Builder name(String name) {
            if (name != null) {
                String converted = convertLegacyColors(name);
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
         * Sets the slot position for this button.
         * 
         * @param slot the slot position (0-53 for double chest)
         * @return this builder
         */
        public Builder slot(int slot) {
            this.slot = slot;
            return this;
        }
        
        /**
         * Sets a generic click handler for all click types.
         * 
         * @param handler the click handler
         * @return this builder
         */
        public Builder onClick(Consumer<Player> handler) {
            if (handler != null) {
                this.clickHandlers.put(null, handler);
            }
            return this;
        }
        
        /**
         * Sets a click handler for a specific click type.
         * 
         * @param clickType the click type
         * @param handler the click handler
         * @return this builder
         */
        public Builder onClick(ClickType clickType, Consumer<Player> handler) {
            if (clickType != null && handler != null) {
                this.clickHandlers.put(clickType, handler);
            }
            return this;
        }
        
        /**
         * Sets the cooldown duration for this button.
         * 
         * @param cooldown the cooldown duration
         * @return this builder
         */
        public Builder cooldown(Duration cooldown) {
            this.cooldown = cooldown;
            return this;
        }
        
        /**
         * Skips adding the automatic "Click to interact!" prompt.
         * 
         * @return this builder
         */
        public Builder skipClickPrompt() {
            this.skipClickPrompt = true;
            return this;
        }
        
        /**
         * Builds the MenuButton.
         * 
         * @return the built MenuButton
         */
        public MenuButton build() {
            return new MenuButton(this);
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