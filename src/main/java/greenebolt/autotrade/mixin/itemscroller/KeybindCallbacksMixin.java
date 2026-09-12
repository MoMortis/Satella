package greenebolt.autotrade.mixin.itemscroller;

import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.CraftFillMode;
import greenebolt.autotrade.DropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** Provides the crafting helper used by Satella's automatic crafting controller. */
@Mixin(targets = "fi.dy.masa.itemscroller.event.KeybindCallbacks", remap = false)
public abstract class KeybindCallbacksMixin {
    /** Runs the residual recipe against the hidden vanilla crafting-table handler. */
    private static void autoTrade$craftHidden(net.minecraft.screen.CraftingScreenHandler handler,
                                              MinecraftClient mc) {
        try {
            if (AutoTradeConfigs.Trade.CRAFT_FILL_MODE.getOptionListValue() == CraftFillMode.RECIPE_BOOK) {
                autoTrade$craftWithRecipeBook(handler, mc);
            } else {
                autoTrade$resetRecipeBookState();
                autoTrade$craftHandler(handler, handler.getSlot(0), 1, 9, mc, 1);
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            AutoTrade.LOGGER.warn("Automatic residual crafting failed", e);
        }
    }

    private static ScreenHandler autoTrade$recipeBookHandler;
    private static Object autoTrade$recipeBookId;
    private static ItemStack autoTrade$recipeBookResult;

    private static void autoTrade$craftWithRecipeBook(net.minecraft.screen.CraftingScreenHandler handler,
                                                       MinecraftClient mc) throws ReflectiveOperationException {
        Object storage = autoTrade$callStatic("fi.dy.masa.itemscroller.recipes.RecipeStorage", "getInstance");
        Object recipe = autoTrade$call(storage, "getSelectedRecipe");
        ItemStack result = (ItemStack) autoTrade$call(recipe, "getResult");
        if (result.isEmpty()) {
            autoTrade$resetRecipeBookState();
            return;
        }

        // 旧配方可能只保存了材料和结果，进入配方书模式时主动重新匹配网络配方编号。
        autoTrade$call(recipe, "storeIdFromClientRecipeBook", mc);
        Object recipeId = autoTrade$call(recipe, "getNetworkRecipeId");
        if (recipeId == null) {
            autoTrade$resetRecipeBookState();
            AutoTrade.LOGGER.warn("Recipe book mode has no network recipe id for selected recipe");
            return;
        }

        boolean changed = autoTrade$recipeBookHandler != handler
                || !Objects.equals(autoTrade$recipeBookId, recipeId)
                || autoTrade$recipeBookResult == null
                || !ItemStack.areItemsAndComponentsEqual(autoTrade$recipeBookResult, result);
        if (changed) {
            autoTrade$recipeBookHandler = handler;
            autoTrade$recipeBookId = recipeId;
            autoTrade$recipeBookResult = result.copy();
        }

        ItemStack output = handler.getSlot(0).getStack();
        if (!output.isEmpty()) {
            if (ItemStack.areItemsAndComponentsEqual(output, autoTrade$recipeBookResult)) {
                autoTrade$throwInternal(handler, 0, 1, mc);
            }
            return;
        }

        int requests = AutoTradeConfigs.Trade.RECIPE_FILL_ITERATIONS.getIntegerValue();
        // 与“配方填入次数”的配置含义一致：本周期连续发送指定次数的普通请求。
        for (int i = 0; i < requests; i++) {
            mc.interactionManager.clickRecipe(handler.syncId, (NetworkRecipeId) recipeId, false);
        }
    }

    private static void autoTrade$resetRecipeBookState() {
        autoTrade$recipeBookHandler = null;
        autoTrade$recipeBookId = null;
        autoTrade$recipeBookResult = null;
    }

    private static void autoTrade$craftHandler(ScreenHandler handler, Slot output, int first, int last,
                                               MinecraftClient mc, int iterations)
            throws ReflectiveOperationException {
        if (first < 0 || last >= handler.slots.size()) {
            return;
        }

        Object storage = autoTrade$callStatic("fi.dy.masa.itemscroller.recipes.RecipeStorage", "getInstance");
        Object recipe = autoTrade$call(storage, "getSelectedRecipe");
        ItemStack result = (ItemStack) autoTrade$call(recipe, "getResult");
        ItemStack[] ingredients = (ItemStack[]) autoTrade$call(recipe, "getRecipeItems");

        if (result.isEmpty()) {
            return;
        }

        int limit = Math.max(1, iterations) * 1024;
        for (int i = 0; i < limit; i++) {
            if (!autoTrade$prepareCraftingGrid(handler, first, last, ingredients, mc)
                    || !ItemStack.areItemsAndComponentsEqual(output.getStack(), result)) {
                return;
            }
            ItemStack[] gridBefore = autoTrade$copyGrid(handler, first, last);
            autoTrade$throwInternal(handler, output.id, 1, mc);
            if (!autoTrade$gridChanged(handler, first, last, gridBefore)) {
                return;
            }
        }
    }

    private static boolean autoTrade$prepareCraftingGrid(ScreenHandler handler, int first, int last,
                                                          ItemStack[] ingredients, MinecraftClient mc) {
        autoTrade$recoverCursor(handler, first, last, ingredients, mc);
        autoTrade$repairMalformedGrid(handler, first, last, ingredients, mc);
        autoTrade$recoverCursor(handler, first, last, ingredients, mc);

        autoTrade$fillMissingRecipeSlots(handler, first, last, ingredients, mc);
        autoTrade$rebalanceCraftingGrid(handler, first, last, ingredients, mc);
        autoTrade$recoverCursor(handler, first, last, ingredients, mc);
        return handler.getCursorStack().isEmpty()
                && autoTrade$gridMatchesRecipe(handler, first, last, ingredients);
    }

    private static void autoTrade$repairMalformedGrid(ScreenHandler handler, int first, int last,
                                                       ItemStack[] ingredients, MinecraftClient mc) {
        // First swap misplaced pairs when both slots become correct after the swap.
        for (int left = 0; left < ingredients.length && first + left <= last; left++) {
            int leftSlot = first + left;
            ItemStack leftStack = handler.getSlot(leftSlot).getStack();
            if (leftStack.isEmpty() || ItemStack.areItemsAndComponentsEqual(leftStack, ingredients[left])) {
                continue;
            }
            for (int right = left + 1; right < ingredients.length && first + right <= last; right++) {
                int rightSlot = first + right;
                ItemStack rightStack = handler.getSlot(rightSlot).getStack();
                if (!rightStack.isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(leftStack, ingredients[right])
                        && ItemStack.areItemsAndComponentsEqual(rightStack, ingredients[left])) {
                    autoTrade$click(handler, leftSlot, 0, SlotActionType.PICKUP, mc);
                    autoTrade$click(handler, rightSlot, 0, SlotActionType.PICKUP, mc);
                    autoTrade$click(handler, leftSlot, 0, SlotActionType.PICKUP, mc);
                    if (!handler.getCursorStack().isEmpty()) {
                        autoTrade$returnCursorToInventoryOrDrop(handler, first, last, mc);
                    }
                    break;
                }
            }
        }

        // Move misplaced items only into empty slots that expect that item.
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            int sourceSlot = first + index;
            ItemStack source = handler.getSlot(sourceSlot).getStack();
            if (source.isEmpty() || ItemStack.areItemsAndComponentsEqual(source, ingredients[index])) {
                continue;
            }
            for (int targetIndex = 0; targetIndex < ingredients.length; targetIndex++) {
                int targetSlot = first + targetIndex;
                if (!ingredients[targetIndex].isEmpty()
                        && handler.getSlot(targetSlot).getStack().isEmpty()
                        && ItemStack.areItemsAndComponentsEqual(source, ingredients[targetIndex])) {
                    autoTrade$click(handler, sourceSlot, 0, SlotActionType.PICKUP, mc);
                    autoTrade$click(handler, targetSlot, 0, SlotActionType.PICKUP, mc);
                    autoTrade$recoverCursor(handler, first, last, ingredients, mc);
                    break;
                }
            }
        }

        // Remaining misplaced items go to the player inventory, then to the ground.
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            int slot = first + index;
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(stack, ingredients[index])) {
                autoTrade$click(handler, slot, 0, SlotActionType.PICKUP, mc);
                autoTrade$returnCursorToInventoryOrDrop(handler, first, last, mc);
            }
        }
    }

    private static boolean autoTrade$gridMatchesRecipe(ScreenHandler handler, int first, int last,
                                                        ItemStack[] ingredients) {
        for (int index = 0; index < ingredients.length; index++) {
            int slot = first + index;
            if (slot > last) {
                return false;
            }
            ItemStack expected = ingredients[index];
            ItemStack actual = handler.getSlot(slot).getStack();
            if (expected.isEmpty() ? !actual.isEmpty() : !ItemStack.areItemsAndComponentsEqual(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private static void autoTrade$recoverCursor(ScreenHandler handler, int first, int last,
                                                 ItemStack[] ingredients, MinecraftClient mc) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            ItemStack expected = ingredients[index];
            if (!expected.isEmpty() && handler.getSlot(first + index).getStack().isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), expected)) {
                autoTrade$click(handler, first + index, 0, SlotActionType.PICKUP, mc);
                break;
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
            autoTrade$returnCursorToInventoryOrDrop(handler, first, last, mc);
        }
    }

    private static ScreenHandler autoTrade$scanHandler;
    private static ItemStack[] autoTrade$scanRecipe;
    private static int autoTrade$scanCursor = -1;

    /** Scans the inventory once with one shared cursor for every recipe ingredient. */
    private static boolean autoTrade$fillMissingRecipeSlots(ScreenHandler handler, int first, int last,
                                                            ItemStack[] ingredients, MinecraftClient mc) {
        autoTrade$prepareScanState(handler, ingredients);

        int slotCount = handler.slots.size();
        if (slotCount == 0) {
            return false;
        }

        // 建材料表：每格记录还差多少个才补满（M = 材料最大堆叠上限 - 格内已有数量）
        int[] deficit = new int[ingredients.length];
        for (int recipeIndex = 0; recipeIndex < ingredients.length; recipeIndex++) {
            int slot = first + recipeIndex;
            ItemStack gridStack = slot <= last ? handler.getSlot(slot).getStack() : ItemStack.EMPTY;
            int present = !ingredients[recipeIndex].isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(gridStack, ingredients[recipeIndex])
                    ? gridStack.getCount() : 0;
            deficit[recipeIndex] = ingredients[recipeIndex].isEmpty() || slot > last
                    ? 0 : ingredients[recipeIndex].getMaxCount() - present;
        }

        int start = (autoTrade$scanCursor + 1 + slotCount) % slotCount;
        boolean moved = false;
        for (int offset = 0; offset < slotCount; offset++) {
            int source = (start + offset) % slotCount;
            autoTrade$scanCursor = source;
            if (autoTrade$tableComplete(deficit)) {
                // 材料补满提前结束时，把游标回退到最后一个实际检查过的槽位，
                // 让当前这个没检查过的槽位下一轮第一个被查，避免被跳过
                autoTrade$scanCursor = (source - 1 + slotCount) % slotCount;
                return true;
            }
            if (source >= first && source <= last) {
                continue;
            }

            ItemStack sourceStack = handler.getSlot(source).getStack();
            if (sourceStack.isEmpty()) {
                continue;
            }
            int residue = AutoTradeConfigs.Trade.CRAFT_RESIDUE.getIntegerValue();
            if (sourceStack.getCount() < 2 * residue) {
                continue;
            }

            // 查材料表：这堆物品要补哪个合成格
            int target = -1;
            for (int recipeIndex = 0; recipeIndex < ingredients.length; recipeIndex++) {
                if (deficit[recipeIndex] <= 0
                        || !ItemStack.areItemsAndComponentsEqual(sourceStack, ingredients[recipeIndex])) {
                    continue;
                }
                ItemStack gridStack = handler.getSlot(first + recipeIndex).getStack();
                if (!gridStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(gridStack, ingredients[recipeIndex])) {
                    continue;
                }
                target = recipeIndex;
                break;
            }
            if (target < 0) {
                continue;
            }

            // 只有当物品堆的一半不超过该格缺口时才取，保证取出来刚好补得进格子，不会有多余
            int half = (sourceStack.getCount() + 1) / 2;
            if (half > deficit[target]) {
                continue;
            }

            // 右键拾取一半到光标；拾到多少就在材料表里扣多少，扣到 0 以下才认定该格补满
            autoTrade$click(handler, source, 1, SlotActionType.PICKUP, mc);
            int picked = handler.getCursorStack().getCount();
            if (picked == 0 || !ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), ingredients[target])) {
                autoTrade$returnCursorToInventoryOrDrop(handler, first, last, mc);
                continue;
            }
            deficit[target] -= picked;
            // 左键把光标物品放进目标合成格
            autoTrade$click(handler, first + target, 0, SlotActionType.PICKUP, mc);
            moved = true;

            // 光标还有剩余（例如本地与服务器状态不一致）：进背包，背包放不下才丢弃
            if (!handler.getCursorStack().isEmpty()) {
                autoTrade$returnCursorToInventoryOrDrop(handler, first, last, mc);
            }
        }
        return moved;
    }

    /** 材料表里所有格子的缺口都已扣减到 0 以下，即补料完成 */
    private static boolean autoTrade$tableComplete(int[] deficit) {
        for (int value : deficit) {
            if (value > 0) {
                return false;
            }
        }
        return true;
    }

    private static void autoTrade$prepareScanState(ScreenHandler handler, ItemStack[] ingredients) {
        if (autoTrade$scanHandler == handler
                && autoTrade$sameRecipe(autoTrade$scanRecipe, ingredients)
                && autoTrade$scanCursor < handler.slots.size()) {
            return;
        }
        autoTrade$scanHandler = handler;
        autoTrade$scanRecipe = autoTrade$copyRecipe(ingredients);
        autoTrade$scanCursor = handler.slots.size() - 1;
    }

    private static boolean autoTrade$sameRecipe(ItemStack[] first, ItemStack[] second) {
        if (first == null || second == null || first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (!ItemStack.areItemsAndComponentsEqual(first[index], second[index])) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack[] autoTrade$copyRecipe(ItemStack[] ingredients) {
        ItemStack[] copy = new ItemStack[ingredients.length];
        for (int index = 0; index < ingredients.length; index++) {
            copy[index] = ingredients[index].copy();
        }
        return copy;
    }

    private static void autoTrade$rebalanceCraftingGrid(ScreenHandler handler, int first, int last,
                                                        ItemStack[] ingredients, MinecraftClient mc) {
        for (int recipeIndex = 0; recipeIndex < ingredients.length; recipeIndex++) {
            ItemStack ingredient = ingredients[recipeIndex];
            if (ingredient.isEmpty() || autoTrade$wasHandled(ingredients, recipeIndex)) {
                continue;
            }

            int count = 0;
            int total = 0;
            for (int index = recipeIndex; index < ingredients.length; index++) {
                int slot = first + index;
                if (slot <= last && ItemStack.areItemsAndComponentsEqual(ingredient, ingredients[index])) {
                    count++;
                    ItemStack stack = handler.getSlot(slot).getStack();
                    if (ItemStack.areItemsAndComponentsEqual(stack, ingredient)) {
                        total += stack.getCount();
                    }
                }
            }
            if (count == 0 || total < count) {
                continue;
            }

            int base = total / count;
            int remainder = total % count;
            for (int targetIndex = recipeIndex, position = 0; targetIndex < ingredients.length; targetIndex++) {
                int targetSlot = first + targetIndex;
                if (targetSlot > last || !ItemStack.areItemsAndComponentsEqual(ingredient, ingredients[targetIndex])) {
                    continue;
                }
                int desired = base + (position++ < remainder ? 1 : 0);
                ItemStack target = handler.getSlot(targetSlot).getStack();
                int current = ItemStack.areItemsAndComponentsEqual(target, ingredient) ? target.getCount() : 0;
                if (current >= desired) {
                    continue;
                }

                int donorSlot = autoTrade$findGridDonor(handler, first, last, ingredients, ingredient, base);
                if (donorSlot < 0) {
                    return;
                }
                autoTrade$moveGridItems(handler, donorSlot, targetSlot, desired - current, mc);
            }
        }
    }

    private static int autoTrade$findGridDonor(ScreenHandler handler, int first, int last,
                                               ItemStack[] ingredients, ItemStack ingredient, int base) {
        for (int index = 0; index < ingredients.length; index++) {
            int slot = first + index;
            if (slot <= last && ItemStack.areItemsAndComponentsEqual(ingredient, ingredients[index])) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (ItemStack.areItemsAndComponentsEqual(stack, ingredient) && stack.getCount() > base) {
                    return slot;
                }
            }
        }
        return -1;
    }

    private static void autoTrade$moveGridItems(ScreenHandler handler, int source, int target,
                                                int amount, MinecraftClient mc) {
        autoTrade$click(handler, source, 0, SlotActionType.PICKUP, mc);
        autoTrade$click(handler, source, 1, SlotActionType.PICKUP, mc);
        for (int i = 0; i < amount && !handler.getCursorStack().isEmpty(); i++) {
            autoTrade$click(handler, target, 1, SlotActionType.PICKUP, mc);
        }
        if (!handler.getCursorStack().isEmpty()) {
            autoTrade$click(handler, source, 0, SlotActionType.PICKUP, mc);
        }
    }

    private static boolean autoTrade$wasHandled(ItemStack[] ingredients, int index) {
        for (int previous = 0; previous < index; previous++) {
            if (ItemStack.areItemsAndComponentsEqual(ingredients[index], ingredients[previous])) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack[] autoTrade$copyGrid(ScreenHandler handler, int first, int last) {
        ItemStack[] grid = new ItemStack[last - first + 1];
        for (int index = 0; index < grid.length; index++) {
            grid[index] = handler.getSlot(first + index).getStack().copy();
        }
        return grid;
    }

    private static boolean autoTrade$gridChanged(ScreenHandler handler, int first, int last,
                                                  ItemStack[] before) {
        if (before.length != last - first + 1) {
            return true;
        }
        for (int index = 0; index < before.length; index++) {
            ItemStack after = handler.getSlot(first + index).getStack();
            ItemStack previous = before[index];
            if (!ItemStack.areItemsAndComponentsEqual(previous, after)
                    || previous.getCount() != after.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static void autoTrade$throwInternal(ScreenHandler handler, int slot, int button,
                                                  MinecraftClient mc) {
        boolean previous = DropBlock.suppressInternal;
        DropBlock.suppressInternal = true;
        try {
            autoTrade$click(handler, slot, button, SlotActionType.THROW, mc);
        } finally {
            DropBlock.suppressInternal = previous;
        }
    }
    private static void autoTrade$returnCursorToInventoryOrDrop(ScreenHandler handler, int first, int last,
                                                                  MinecraftClient mc) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }
        for (int slot = last + 1; slot < handler.slots.size() && !handler.getCursorStack().isEmpty(); slot++) {
            ItemStack target = handler.getSlot(slot).getStack();
            ItemStack carried = handler.getCursorStack();
            if (!target.isEmpty() && (!ItemStack.areItemsAndComponentsEqual(target, carried)
                    || target.getCount() >= target.getMaxCount())) {
                continue;
            }
            autoTrade$click(handler, slot, 0, SlotActionType.PICKUP, mc);
        }
        if (!handler.getCursorStack().isEmpty()) {
            autoTrade$throwInternal(handler, -999, 0, mc);
        }
    }

    private static boolean autoTrade$isMassCraftKeysDown(MinecraftClient mc) {
        return (InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL))
                && (InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT))
                && InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_C);
    }

    private static int autoTrade$getItemScrollerInteger(String fieldName) throws ReflectiveOperationException {
        Class<?> generic = Class.forName("fi.dy.masa.itemscroller.config.Configs$Generic");
        Object config = generic.getField(fieldName).get(null);
        return (int) autoTrade$call(config, "getIntegerValue");
    }

    private static void autoTrade$click(ScreenHandler handler, int slot, int button,
                                        SlotActionType action, MinecraftClient mc) {
        mc.interactionManager.clickSlot(handler.syncId, slot, button, action, mc.player);
    }

    private static Object autoTrade$callStatic(String className, String method, Object... arguments)
            throws ReflectiveOperationException {
        return autoTrade$call(Class.forName(className), null, method, arguments);
    }

    private static Object autoTrade$call(Object instance, String method, Object... arguments)
            throws ReflectiveOperationException {
        return autoTrade$call(instance.getClass(), instance, method, arguments);
    }

    private static Object autoTrade$call(Class<?> owner, Object instance, String name, Object... arguments)
            throws ReflectiveOperationException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length
                    && (instance != null || Modifier.isStatic(method.getModifiers()))) {
                return method.invoke(instance, arguments);
            }
        }
        throw new NoSuchMethodException(owner.getName() + '#' + name);
    }
}
