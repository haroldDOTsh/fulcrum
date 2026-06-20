package sh.harold.fulcrum.host.paper;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Objects;

final class PaperHostMenuHolder implements InventoryHolder {
    private PaperHostMenuController.OpenedMenu activeMenu;
    private Inventory inventory;

    PaperHostMenuHolder(PaperHostMenuController.OpenedMenu activeMenu) {
        this.activeMenu = Objects.requireNonNull(activeMenu, "activeMenu");
    }

    PaperHostMenuController.OpenedMenu activeMenu() {
        return activeMenu;
    }

    void activeMenu(PaperHostMenuController.OpenedMenu activeMenu) {
        this.activeMenu = Objects.requireNonNull(activeMenu, "activeMenu");
    }

    void bindInventory(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Paper host menu inventory has not been bound");
        }
        return inventory;
    }
}
