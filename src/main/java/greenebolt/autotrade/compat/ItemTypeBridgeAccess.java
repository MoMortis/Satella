package greenebolt.autotrade.compat;

import net.minecraft.component.ComponentMap;
import net.minecraft.item.Item;

/**
 * 由 Inventory Profiles Next 的 ItemType 通过 Mixin 实现，
 * 用于在不直接依赖 IPN 类的情况下读取物品与组件。
 */
public interface ItemTypeBridgeAccess {
    Item autoTrade$item();

    ComponentMap autoTrade$components();
}
