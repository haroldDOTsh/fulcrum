package sh.harold.fulcrum.npc.options;

import org.bukkit.inventory.ItemStack;

/**
 * Snapshot of the equipment an NPC should render with.
 */
public final class NpcEquipment {
    private final ItemStack mainHand;
    private final ItemStack offHand;
    private final ItemStack helmet;
    private final ItemStack chestplate;
    private final ItemStack leggings;
    private final ItemStack boots;

    private NpcEquipment(Builder builder) {
        this.mainHand = copy(builder.mainHand);
        this.offHand = copy(builder.offHand);
        this.helmet = copy(builder.helmet);
        this.chestplate = copy(builder.chestplate);
        this.leggings = copy(builder.leggings);
        this.boots = copy(builder.boots);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NpcEquipment empty() {
        return builder().build();
    }

    private static ItemStack copy(ItemStack input) {
        return input == null ? null : input.clone();
    }

    public ItemStack mainHand() {
        return copy(mainHand);
    }

    public ItemStack offHand() {
        return copy(offHand);
    }

    public ItemStack helmet() {
        return copy(helmet);
    }

    public ItemStack chestplate() {
        return copy(chestplate);
    }

    public ItemStack leggings() {
        return copy(leggings);
    }

    public ItemStack boots() {
        return copy(boots);
    }

    public static final class Builder {
        private ItemStack mainHand;
        private ItemStack offHand;
        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;

        public Builder mainHand(ItemStack mainHand) {
            this.mainHand = copy(mainHand);
            return this;
        }

        public Builder offHand(ItemStack offHand) {
            this.offHand = copy(offHand);
            return this;
        }

        public Builder helmet(ItemStack helmet) {
            this.helmet = copy(helmet);
            return this;
        }

        public Builder chestplate(ItemStack chestplate) {
            this.chestplate = copy(chestplate);
            return this;
        }

        public Builder leggings(ItemStack leggings) {
            this.leggings = copy(leggings);
            return this;
        }

        public Builder boots(ItemStack boots) {
            this.boots = copy(boots);
            return this;
        }

        public NpcEquipment build() {
            return new NpcEquipment(this);
        }
    }
}
