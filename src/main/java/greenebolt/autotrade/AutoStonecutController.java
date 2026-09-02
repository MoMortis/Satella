package greenebolt.autotrade;

import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.recipe.display.CuttingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class AutoStonecutController {
    private static final int INPUT_SLOT = StonecutterScreenHandler.INPUT_ID;
    private static final int OUTPUT_SLOT = StonecutterScreenHandler.OUTPUT_ID;

    private static int openCooldown;
    private static int stonecutTicker;
    /** “未找到目标配方”在物品栏上方停留的剩余刻数，期间不再覆盖显示“全自动切石中...” */
    private static int missingMessageTicks;

    // 环形扫描游标：与全自动合成相同，记住上次扫到的位置，下次从下一个槽位继续
    private static ScreenHandler scanHandler;
    private static String scanInputKey;
    private static int scanCursor = -1;

    private AutoStonecutController() {}

    public static boolean isActive() {
        return AutoTradeConfigs.Trade.AUTOMATION.getBooleanValue()
                && AutoTradeConfigs.Trade.AUTOMATION_MODE.getOptionListValue() == AutomationMode.STONECUTTING;
    }

    public static void tick(MinecraftClient mc) {
        if (!isActive() || mc.player == null || mc.world == null || mc.interactionManager == null) {
            stonecutTicker = 0;
            return;
        }

        if (missingMessageTicks > 0) {
            missingMessageTicks--;
        } else {
            InfoUtils.sendVanillaMessage(Text.literal("全自动切石中...").formatted(Formatting.GREEN));
        }

        if (mc.player.currentScreenHandler instanceof StonecutterScreenHandler handler) {
            if (++stonecutTicker >= AutoTradeConfigs.Trade.AUTOMATION_INTERVAL.getIntegerValue()) {
                stonecutTicker = 0;
                stonecutHidden(handler, mc);
            }
            return;
        }

        stonecutTicker = 0;
        if (openCooldown > 0) {
            --openCooldown;
            return;
        }

        BlockPos cutter = findStonecutter(mc);
        if (cutter != null) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(cutter), Direction.UP, cutter, false));
        }
        openCooldown = 10;
    }

    private static BlockPos findStonecutter(MinecraftClient mc) {
        BlockPos center = mc.player.getBlockPos();
        double maxDistanceSquared = 4.5 * 4.5;
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!mc.world.getBlockState(pos).isOf(Blocks.STONECUTTER)) {
                        continue;
                    }
                    double distance = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance <= maxDistanceSquared && distance < closestDistance) {
                        closest = pos;
                        closestDistance = distance;
                    }
                }
            }
        }
        return closest;
    }

    private static void stonecutHidden(StonecutterScreenHandler handler, MinecraftClient mc) {
        if (!handler.getCursorStack().isEmpty()) {
            returnCursorToInventoryOrDrop(handler, mc);
            return;
        }

        String inputKey = AutoTradeConfigs.Trade.STONECUTTING_INPUT.getStringValue();
        ItemStack input = handler.getSlot(INPUT_SLOT).getStack();
        if (!input.isEmpty() && !ItemNameUtils.matches(inputKey, input.getItem())) {
            // 输入槽里是错误物品：放回背包
            click(handler, INPUT_SLOT, 0, SlotActionType.PICKUP, mc);
            returnCursorToInventoryOrDrop(handler, mc);
            return;
        }
        if (input.isEmpty()) {
            if (!inputKey.isBlank()) {
                placeInput(handler, inputKey, mc);
            }
            return;
        }

        String outputKey = AutoTradeConfigs.Trade.STONECUTTING_OUTPUT.getStringValue();
        ItemStack output = handler.getSlot(OUTPUT_SLOT).getStack();
        if (!output.isEmpty() && ItemNameUtils.matches(outputKey, output.getItem())) {
            // button=1：原版会对同一输出槽循环取出，直到原料耗尽，全部丢出
            throwInternal(handler, OUTPUT_SLOT, 1, mc);
            return;
        }

        int desired = findRecipeIndex(handler, outputKey, mc);
        if (desired < 0) {
            missingMessageTicks = 40;
            InfoUtils.sendVanillaMessage(Text.literal("未找到目标配方").formatted(Formatting.RED));
            return;
        }
        if (handler.getSelectedRecipe() != desired) {
            mc.interactionManager.clickButton(handler.syncId, desired);
        }
    }

    private static void placeInput(StonecutterScreenHandler handler, String inputKey, MinecraftClient mc) {
        int slotCount = handler.slots.size();
        if (slotCount == 0) {
            return;
        }
        prepareScanState(handler, inputKey);
        int start = (scanCursor + 1 + slotCount) % slotCount;
        for (int offset = 0; offset < slotCount; offset++) {
            int source = (start + offset) % slotCount;
            scanCursor = source;
            if (source == INPUT_SLOT || source == OUTPUT_SLOT) {
                continue;
            }
            ItemStack sourceStack = handler.getSlot(source).getStack();
            if (sourceStack.isEmpty() || !ItemNameUtils.matches(inputKey, sourceStack.getItem())) {
                continue;
            }
            // 与全自动合成相同：数量过少的材料堆不动，避免把零散的原料拆散
            int residue = AutoTradeConfigs.Trade.CRAFT_RESIDUE.getIntegerValue();
            if (sourceStack.getCount() < 2 * residue) {
                continue;
            }

            ItemStack input = handler.getSlot(INPUT_SLOT).getStack();
            int amount = (sourceStack.getCount() + 1) / 2;
            if (!input.isEmpty() && input.getCount() + amount > input.getMaxCount()) {
                return;
            }

            click(handler, source, 1, SlotActionType.PICKUP, mc);
            if (!ItemNameUtils.matches(inputKey, handler.getCursorStack().getItem())) {
                returnCursorToInventoryOrDrop(handler, mc);
                continue;
            }
            click(handler, INPUT_SLOT, 0, SlotActionType.PICKUP, mc);
            if (!handler.getCursorStack().isEmpty()) {
                click(handler, source, 0, SlotActionType.PICKUP, mc);
            }
            if (!handler.getCursorStack().isEmpty()) {
                returnCursorToInventoryOrDrop(handler, mc);
            }
            return;
        }
    }

    private static int findRecipeIndex(StonecutterScreenHandler handler, String outputKey, MinecraftClient mc) {
        if (outputKey.isBlank() || mc.world == null) {
            return -1;
        }
        CuttingRecipeDisplay.Grouping<StonecuttingRecipe> recipes = handler.getAvailableRecipes();
        if (recipes.isEmpty()) {
            return -1;
        }
        ContextParameterMap context = SlotDisplayContexts.createParameters(mc.world);
        for (int index = 0; index < recipes.size(); index++) {
            ItemStack display = recipes.entries().get(index).recipe().optionDisplay().getFirst(context);
            if (!display.isEmpty() && ItemNameUtils.matches(outputKey, display.getItem())) {
                return index;
            }
        }
        return -1;
    }

    private static void prepareScanState(ScreenHandler handler, String inputKey) {
        if (scanHandler == handler
                && inputKey.equals(scanInputKey)
                && scanCursor < handler.slots.size()) {
            return;
        }
        scanHandler = handler;
        scanInputKey = inputKey;
        scanCursor = handler.slots.size() - 1;
    }

    private static void returnCursorToInventoryOrDrop(ScreenHandler handler, MinecraftClient mc) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }
        for (int slot = OUTPUT_SLOT + 1; slot < handler.slots.size() && !handler.getCursorStack().isEmpty(); slot++) {
            ItemStack target = handler.getSlot(slot).getStack();
            ItemStack carried = handler.getCursorStack();
            if (!target.isEmpty() && (!ItemStack.areItemsAndComponentsEqual(target, carried)
                    || target.getCount() >= target.getMaxCount())) {
                continue;
            }
            click(handler, slot, 0, SlotActionType.PICKUP, mc);
        }
        if (!handler.getCursorStack().isEmpty()) {
            throwInternal(handler, -999, 0, mc);
        }
    }

    private static void throwInternal(ScreenHandler handler, int slot, int button, MinecraftClient mc) {
        boolean previous = DropBlock.suppressInternal;
        DropBlock.suppressInternal = true;
        try {
            click(handler, slot, button, SlotActionType.THROW, mc);
        } finally {
            DropBlock.suppressInternal = previous;
        }
    }

    private static void click(ScreenHandler handler, int slot, int button,
                              SlotActionType action, MinecraftClient mc) {
        mc.interactionManager.clickSlot(handler.syncId, slot, button, action, mc.player);
    }

    /** 关闭隐藏的切石机容器，供自动化开关/模式切换调用 */
    public static void close(MinecraftClient mc) {
        if (mc.player == null || !(mc.player.currentScreenHandler instanceof StonecutterScreenHandler)) {
            return;
        }
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (networkHandler != null) {
            networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
        mc.player.currentScreenHandler.onClosed(mc.player);
        mc.player.currentScreenHandler = mc.player.playerScreenHandler;
    }
}
