package greenebolt.autotrade.mixin.itemscroller;

import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.AutoTradeConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
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

/** Replaces Item Scroller's mass-craft paths while residual crafting is enabled. */
@Mixin(targets = "fi.dy.masa.itemscroller.event.KeybindCallbacks", remap = false)
public abstract class KeybindCallbacksMixin {
    @Shadow protected int massCraftTicker;

    @Inject(method = "onClientTickMassCraftImpl", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoTrade$residualMassCraft(MinecraftClient mc, CallbackInfo ci) {
        if (!AutoTradeConfigs.Trade.RESIDUAL_CRAFTING.getBooleanValue()) {
            return;
        }

        // Do not allow Item Scroller's recipe-book, swaps, or fallback paths to run.
        ci.cancel();

        if (mc.player == null || mc.interactionManager == null
                || !(mc.currentScreen instanceof HandledScreen<?> gui)
                || !autoTrade$isMassCraftKeysDown(mc)) {
            return;
        }

        try {
            int interval = autoTrade$getItemScrollerInteger("MASS_CRAFT_INTERVAL");
            if (++this.massCraftTicker < interval) {
                return;
            }
            this.massCraftTicker = 0;
            autoTrade$craftOnce(gui, mc, autoTrade$getItemScrollerInteger("MASS_CRAFT_ITERATIONS"));
        } catch (ReflectiveOperationException | ClassCastException e) {
            AutoTrade.LOGGER.warn("Residual mass crafting failed", e);
        }
    }

    private static void autoTrade$craftOnce(HandledScreen<?> gui, MinecraftClient mc, int iterations)
            throws ReflectiveOperationException {
        ScreenHandler handler = gui.getScreenHandler();
        Slot output = (Slot) autoTrade$callStatic(
                "fi.dy.masa.itemscroller.recipes.CraftingHandler",
                "getFirstCraftingOutputSlotForGui", gui);
        if (output == null) {
            return;
        }

        Object range = autoTrade$callStatic(
                "fi.dy.masa.itemscroller.recipes.CraftingHandler",
                "getCraftingGridSlots", gui, output);
        if (range == null) {
            return;
        }

        int first = (int) autoTrade$call(range, "getFirst");
        int last = (int) autoTrade$call(range, "getLast");
        autoTrade$craftHandler(handler, output, first, last, mc, iterations);
    }

    /** Runs the residual recipe against the hidden vanilla crafting-table handler. */
    private static void autoTrade$craftHidden(net.minecraft.screen.CraftingScreenHandler handler,
                                              MinecraftClient mc) {
        try {
            autoTrade$craftHandler(handler, handler.getSlot(0), 1, 9, mc,
                    autoTrade$getItemScrollerInteger("MASS_CRAFT_ITERATIONS"));
        } catch (ReflectiveOperationException | ClassCastException e) {
            AutoTrade.LOGGER.warn("Automatic residual crafting failed", e);
        }
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

        for (int i = 0; i < iterations; i++) {
            if (!autoTrade$prepareCraftingGrid(handler, first, last, ingredients, mc)
                    || !ItemStack.areItemsAndComponentsEqual(output.getStack(), result)) {
                return;
            }
            autoTrade$click(handler, output.id, 1, SlotActionType.THROW, mc);
        }
    }

    private static boolean autoTrade$prepareCraftingGrid(ScreenHandler handler, int first, int last,
                                                          ItemStack[] ingredients, MinecraftClient mc) {
        autoTrade$clearCursor(handler, first, last, mc);
        autoTrade$removeUnexpectedGridItems(handler, first, last, ingredients, mc);
        autoTrade$clearCursor(handler, first, last, mc);
        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }

        autoTrade$fillMissingRecipeSlots(handler, first, last, ingredients, mc);
        autoTrade$rebalanceCraftingGrid(handler, first, last, ingredients, mc);
        autoTrade$clearCursor(handler, first, last, mc);
        return handler.getCursorStack().isEmpty()
                && autoTrade$gridMatchesRecipe(handler, first, last, ingredients);
    }

    private static void autoTrade$removeUnexpectedGridItems(ScreenHandler handler, int first, int last,
                                                             ItemStack[] ingredients, MinecraftClient mc) {
        for (int index = 0; index < ingredients.length; index++) {
            int slot = first + index;
            if (slot > last) {
                return;
            }
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && (ingredients[index].isEmpty()
                    || !ItemStack.areItemsAndComponentsEqual(stack, ingredients[index]))) {
                autoTrade$click(handler, slot, 0, SlotActionType.PICKUP, mc);
                autoTrade$clearCursor(handler, first, last, mc);
                if (!handler.getCursorStack().isEmpty()) {
                    return;
                }
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

    /** One pass over the backpack per prepare. Each matching backpack stack is
     * compared against the crafting grid per slot: if half of the backpack stack
     * plus the slot's current contents still fits within the item's max stack size,
     * the half is right-clicked out and placed whole into that slot; otherwise the
     * stack is skipped and the next one is checked. Afterwards the grid is
     * rebalanced to an even, valid layout before crafting. */
    private static void autoTrade$fillMissingRecipeSlots(ScreenHandler handler, int first, int last,
                                                         ItemStack[] ingredients, MinecraftClient mc) {
        for (int recipeIndex = 0; recipeIndex < ingredients.length; recipeIndex++) {
            ItemStack ingredient = ingredients[recipeIndex];
            if (ingredient.isEmpty() || autoTrade$wasHandled(ingredients, recipeIndex)) {
                continue;
            }

            int maxStack = ingredient.getMaxCount();
            int slotCount = 0;
            for (int index = 0; index < ingredients.length; index++) {
                if (ItemStack.areItemsAndComponentsEqual(ingredient, ingredients[index])) {
                    slotCount++;
                }
            }
            int[] slots = new int[slotCount];
            for (int index = 0, s = 0; index < ingredients.length; index++) {
                if (ItemStack.areItemsAndComponentsEqual(ingredient, ingredients[index])) {
                    slots[s++] = first + index;
                }
            }

        int rotation = 0;
        int reserve = AutoTradeConfigs.Trade.CRAFT_RESIDUE.getIntegerValue();
        for (int source = 0; source < handler.slots.size(); source++) {
            if (source >= first && source <= last) {
                continue;
            }
            ItemStack sourceStack = handler.getSlot(source).getStack();
            if (sourceStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(sourceStack, ingredient)) {
                continue;
            }
            // 合成残余：0.5*P < reserve 时整堆跳过，继续遍历下一个
            if (sourceStack.getCount() < 2 * reserve) {
                continue;
            }

                int half = (sourceStack.getCount() + 1) / 2;
                int target = -1;
                for (int offset = 0; offset < slots.length && target < 0; offset++) {
                    int slotId = slots[(rotation + offset) % slots.length];
                    ItemStack gridStack = handler.getSlot(slotId).getStack();
                    if (!gridStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(gridStack, ingredient)) {
                        continue;
                    }
                    if (gridStack.getCount() + half <= maxStack) {
                        target = slotId;
                        rotation = (rotation + offset + 1) % slots.length;
                    }
                }
                if (target < 0) {
                    // Half of this stack would overflow every recipe slot: skip it.
                    continue;
                }

                // Right-click take half (a 1-count stack is taken whole).
                autoTrade$click(handler, source, 1, SlotActionType.PICKUP, mc);
                if (!ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), ingredient)) {
                    autoTrade$clearCursor(handler, first, last, mc);
                    return;
                }
                // Place the whole half onto the target slot.
                autoTrade$click(handler, target, 0, SlotActionType.PICKUP, mc);
                if (!handler.getCursorStack().isEmpty()) {
                    autoTrade$click(handler, source, 0, SlotActionType.PICKUP, mc);
                }
                if (!handler.getCursorStack().isEmpty()) {
                    autoTrade$clearCursor(handler, first, last, mc);
                    return;
                }
            }
        }
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

    private static void autoTrade$clearCursor(ScreenHandler handler, int first, int last, MinecraftClient mc) {
        if (handler.getCursorStack().isEmpty()) {
            return;
        }
        // First merge into matching backpack stacks, then use an empty backpack slot.
        for (int slot = 0; slot < handler.slots.size() && !handler.getCursorStack().isEmpty(); slot++) {
            if (slot >= first && slot <= last) {
                continue;
            }
            Slot target = handler.getSlot(slot);
            if (!target.getStack().isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(target.getStack(), handler.getCursorStack())
                    && target.canInsert(handler.getCursorStack())) {
                autoTrade$click(handler, slot, 0, SlotActionType.PICKUP, mc);
            }
        }
        for (int slot = 0; slot < handler.slots.size() && !handler.getCursorStack().isEmpty(); slot++) {
            if (slot >= first && slot <= last) {
                continue;
            }
            Slot target = handler.getSlot(slot);
            if (target.getStack().isEmpty() && target.canInsert(handler.getCursorStack())) {
                autoTrade$click(handler, slot, 0, SlotActionType.PICKUP, mc);
            }
        }
        if (!handler.getCursorStack().isEmpty()) {
            autoTrade$click(handler, -999, 0, SlotActionType.PICKUP, mc);
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
