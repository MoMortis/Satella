package greenebolt.autotrade;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** 拦截目标物品丢弃：仅保留“光标移出界面丢弃”一种方式，手持 Q / Ctrl+Q、容器内 Q / Ctrl+Q 等全部拦截 */
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
        // slotId 为 -999 表示光标移出界面丢弃光标上的物品，这是唯一放行的丢弃方式；
        // 其余 THROW（背包/容器内按 Q、Ctrl+Q 等）落在具体槽位上，全部拦截
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return false;
        }
        ItemStack stack = handler.getSlot(slotId).getStack();
        return !stack.isEmpty() && isBlockedItem(stack.getItem());
    }
}
