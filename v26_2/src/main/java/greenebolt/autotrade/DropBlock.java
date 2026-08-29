package greenebolt.autotrade;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 拦截目标物品丢弃：手持 Q / Ctrl+Q、容器内 Q / Ctrl+Q、光标移出界面丢弃等 */
public final class DropBlock {
    private DropBlock() {}

    /** 模组内部逻辑触发的 THROW（如残差合成取产物）不拦截 */
    public static boolean suppressInternal = false;

    public static boolean isBlockedItem(Item item) {
        for (String entry : AutoTradeConfigs.Trade.DROP_BLOCK_ITEMS.getStrings()) {
            Item parsed = ItemNameUtils.parseItem(entry);
            if (parsed != null && parsed == item) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHandDropBlocked(ItemStack stack) {
        return !suppressInternal && !stack.isEmpty() && isBlockedItem(stack.getItem());
    }

    public static boolean isSlotDropBlocked(AbstractContainerMenu menu, int slotId, ContainerInput input) {
        if (suppressInternal || input != ContainerInput.THROW) {
            return false;
        }
        ItemStack stack;
        if (slotId >= 0 && slotId < menu.slots.size()) {
            stack = menu.getSlot(slotId).getItem();
        } else {
            // slotId 为 -999（光标移出界面点击）等负值时丢弃的是光标上的物品
            stack = menu.getCarried();
        }
        return !stack.isEmpty() && isBlockedItem(stack.getItem());
    }
}
