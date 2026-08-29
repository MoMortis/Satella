package greenebolt.autotrade;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** 拦截目标物品丢弃：手持 Q / Ctrl+Q、容器内 Q / Ctrl+Q、光标移出界面丢弃等 */
public final class DropBlock {
    private DropBlock() {}

    /** 模组内部逻辑触发的 THROW（如残差合成取产物）不拦截 */
    public static boolean suppressInternal = false;

    public static boolean isBlockedItem(Item item) {
        for (String entry : AutoTradeConfigs.Trade.DROP_BLOCK_ITEMS.getStrings()) {
            if (ItemNameUtils.matches(entry, item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isHandDropBlocked(ItemStack stack) {
        return !suppressInternal && !stack.isEmpty() && isBlockedItem(stack.getItem());
    }

    public static boolean isSlotDropBlocked(ScreenHandler handler, int slotId, SlotActionType actionType) {
        if (suppressInternal || actionType != SlotActionType.THROW) {
            return false;
        }
        ItemStack stack;
        if (slotId >= 0 && slotId < handler.slots.size()) {
            stack = handler.getSlot(slotId).getStack();
        } else {
            // slotId 为 -999（光标移出界面点击）等负值时丢弃的是光标上的物品
            stack = handler.getCursorStack();
        }
        return !stack.isEmpty() && isBlockedItem(stack.getItem());
    }
}
