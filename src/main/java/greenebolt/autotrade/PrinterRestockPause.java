package greenebolt.autotrade;

import java.lang.reflect.Field;

/**
 * 检测 litematica-printer（litematica-printer-EMT）的"快捷潜影盒-自动补货"是否正在进行。
 *
 * 补货触发链：HandRestockShulkerCompat 判定缺货后 addQuickShulkerDemand(item) + switchItem()，
 * 取货流程期间以下任一信号成立即视为进行中：
 * - InventoryUtils.lastNeedItemList 非空（补货需求挂起，即触发点）
 * - InventoryUtils.isOpenHandler == true（潜影盒打开取货中）
 * - SwitchItem.reSwitchItem != null（取货完成后待放回手部槽位）
 *
 * 打印机 mod 不在编译期依赖内，全部走反射；mod 未安装时恒返回 false。
 */
public final class PrinterRestockPause {
    private static boolean initialized;
    private static Field lastNeedItemListField;
    private static Field isOpenHandlerField;
    private static Field reSwitchItemField;

    private PrinterRestockPause() {}

    public static boolean isRestockInProgress() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> inventoryUtils = Class.forName(
                        "me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils");
                lastNeedItemListField = inventoryUtils.getField("lastNeedItemList");
                isOpenHandlerField = inventoryUtils.getField("isOpenHandler");
                Class<?> switchItem = Class.forName(
                        "me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem");
                reSwitchItemField = switchItem.getField("reSwitchItem");
            } catch (ReflectiveOperationException e) {
                lastNeedItemListField = null;
                isOpenHandlerField = null;
                reSwitchItemField = null;
            }
        }

        try {
            if (lastNeedItemListField != null && !((java.util.Set<?>) lastNeedItemListField.get(null)).isEmpty()) {
                return true;
            }
            if (isOpenHandlerField != null && isOpenHandlerField.getBoolean(null)) {
                return true;
            }
            return reSwitchItemField != null && reSwitchItemField.get(null) != null;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
