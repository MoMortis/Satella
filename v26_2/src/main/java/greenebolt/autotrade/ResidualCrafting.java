package greenebolt.autotrade;

import fi.dy.masa.itemscroller.recipes.RecipePattern;
import fi.dy.masa.itemscroller.recipes.RecipeStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class ResidualCrafting {
    private ResidualCrafting() {}

    public static boolean craft(AbstractContainerMenu menu, Minecraft minecraft, int firstGridSlot, int lastGridSlot, int iterations) {
        if (minecraft.player == null || minecraft.gameMode == null) return false;
        RecipePattern recipe = RecipeStorage.getInstance().getSelectedRecipe();
        ItemStack result = recipe.getResult();
        ItemStack[] ingredients = recipe.getRecipeItems();
        if (result.isEmpty() || ingredients.length > lastGridSlot - firstGridSlot + 1) return false;
        boolean crafted = false;
        int limit = Math.max(1, iterations) * 1024;
        for (int iteration = 0; iteration < limit; iteration++) {
            if (!prepare(menu, minecraft, firstGridSlot, lastGridSlot, ingredients)) return crafted;
            ItemStack output = menu.getSlot(0).getItem();
            if (!ItemStack.isSameItemSameComponents(output, result)) return crafted;
            ItemStack[] gridBefore = copyGrid(menu, firstGridSlot, lastGridSlot);
            boolean previousSuppress = DropBlock.suppressInternal;
            DropBlock.suppressInternal = true;
            try {
                // 与 Item Scroller 的 dropStack 相同：button=1，尽可能取出输出槽中的整组结果。
                click(minecraft, menu, 0, 1, ContainerInput.THROW);
            } finally {
                DropBlock.suppressInternal = previousSuppress;
            }
            crafted = true;
            if (!gridChanged(menu, firstGridSlot, lastGridSlot, gridBefore)) return crafted;
        }
        return crafted;
    }

    private static boolean prepare(AbstractContainerMenu menu, Minecraft minecraft, int first, int last, ItemStack[] ingredients) {
        recoverCursor(menu, first, last, ingredients, minecraft);
        repairMalformedGrid(menu, first, last, ingredients, minecraft);
        recoverCursor(menu, first, last, ingredients, minecraft);
        for (int index = 0; index < ingredients.length; index++) {
            ItemStack expected = ingredients[index];
            if (expected.isEmpty() || wasHandled(ingredients, index)) continue;
            if (!fillIngredient(menu, minecraft, first, last, ingredients, expected)) return false;
        }
        balanceGrid(menu, minecraft, first, ingredients);
        recoverCursor(menu, first, last, ingredients, minecraft);
        for (int index = ingredients.length; first + index <= last; index++) {
            if (!menu.getSlot(first + index).getItem().isEmpty()) {
                moveToInventory(menu, minecraft, first + index, first, last);
            }
        }
        recoverCursor(menu, first, last, ingredients, minecraft);
        return menu.getCarried().isEmpty() && gridMatchesRecipe(menu, first, last, ingredients);
    }

    private static void repairMalformedGrid(AbstractContainerMenu menu, int first, int last,
                                             ItemStack[] ingredients, Minecraft minecraft) {
        for (int left = 0; left < ingredients.length && first + left <= last; left++) {
            int leftSlot = first + left;
            ItemStack leftStack = menu.getSlot(leftSlot).getItem();
            if (leftStack.isEmpty() || ItemStack.isSameItemSameComponents(leftStack, ingredients[left])) continue;
            for (int right = left + 1; right < ingredients.length && first + right <= last; right++) {
                int rightSlot = first + right;
                ItemStack rightStack = menu.getSlot(rightSlot).getItem();
                if (!rightStack.isEmpty()
                        && ItemStack.isSameItemSameComponents(leftStack, ingredients[right])
                        && ItemStack.isSameItemSameComponents(rightStack, ingredients[left])) {
                    click(minecraft, menu, leftSlot, 0, ContainerInput.PICKUP);
                    click(minecraft, menu, rightSlot, 0, ContainerInput.PICKUP);
                    click(minecraft, menu, leftSlot, 0, ContainerInput.PICKUP);
                    break;
                }
            }
        }
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            int sourceSlot = first + index;
            ItemStack source = menu.getSlot(sourceSlot).getItem();
            if (source.isEmpty() || ItemStack.isSameItemSameComponents(source, ingredients[index])) continue;
            for (int targetIndex = 0; targetIndex < ingredients.length; targetIndex++) {
                int targetSlot = first + targetIndex;
                if (!ingredients[targetIndex].isEmpty() && menu.getSlot(targetSlot).getItem().isEmpty()
                        && ItemStack.isSameItemSameComponents(source, ingredients[targetIndex])) {
                    click(minecraft, menu, sourceSlot, 0, ContainerInput.PICKUP);
                    click(minecraft, menu, targetSlot, 0, ContainerInput.PICKUP);
                    recoverCursor(menu, first, last, ingredients, minecraft);
                    break;
                }
            }
        }
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            int slot = first + index;
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, ingredients[index])) {
                click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
                returnCursorToInventoryOrDrop(menu, first, last, minecraft);
            }
        }
    }

    private static boolean gridMatchesRecipe(AbstractContainerMenu menu, int first, int last, ItemStack[] ingredients) {
        for (int index = 0; index < ingredients.length; index++) {
            int slot = first + index;
            if (slot > last) return false;
            ItemStack expected = ingredients[index];
            ItemStack actual = menu.getSlot(slot).getItem();
            if (expected.isEmpty() ? !actual.isEmpty() : !ItemStack.isSameItemSameComponents(actual, expected)) return false;
        }
        return true;
    }

    private static void recoverCursor(AbstractContainerMenu menu, int first, int last,
                                      ItemStack[] ingredients, Minecraft minecraft) {
        if (menu.getCarried().isEmpty()) return;
        for (int index = 0; index < ingredients.length && first + index <= last; index++) {
            if (!ingredients[index].isEmpty() && menu.getSlot(first + index).getItem().isEmpty()
                    && ItemStack.isSameItemSameComponents(menu.getCarried(), ingredients[index])) {
                click(minecraft, menu, first + index, 0, ContainerInput.PICKUP);
                break;
            }
        }
        if (!menu.getCarried().isEmpty()) returnCursorToInventoryOrDrop(menu, first, last, minecraft);
    }

    private static void returnCursorToInventoryOrDrop(AbstractContainerMenu menu, int first, int last,
                                                       Minecraft minecraft) {
        if (menu.getCarried().isEmpty()) return;
        for (int slot = last + 1; slot < menu.slots.size() && !menu.getCarried().isEmpty(); slot++) {
            ItemStack target = menu.getSlot(slot).getItem();
            ItemStack carried = menu.getCarried();
            if (!target.isEmpty() && (!ItemStack.isSameItemSameComponents(target, carried)
                    || target.getCount() >= target.getMaxStackSize())) continue;
            click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
        }
        if (!menu.getCarried().isEmpty()) {
            boolean previous = DropBlock.suppressInternal;
            DropBlock.suppressInternal = true;
            try {
                click(minecraft, menu, -999, 0, ContainerInput.THROW);
            } finally {
                DropBlock.suppressInternal = previous;
            }
        }
    }

    /** One pass over the backpack per prepare. Each matching backpack stack is
     * compared against the crafting grid per slot: if half of the backpack stack
     * plus the slot's current contents still fits within the item's max stack size,
     * the half is right-clicked out and placed whole into that slot; otherwise the
     * stack is skipped and the next one is checked. */
    private static boolean fillIngredient(AbstractContainerMenu menu, Minecraft minecraft, int first, int last,
                                          ItemStack[] ingredients, ItemStack ingredient) {
        int maxStack = ingredient.getMaxStackSize();
        int slotCount = 0;
        for (int index = 0; index < ingredients.length; index++) {
            if (ItemStack.isSameItemSameComponents(ingredient, ingredients[index])) slotCount++;
        }
        int[] slots = new int[slotCount];
        for (int index = 0, s = 0; index < ingredients.length; index++) {
            if (ItemStack.isSameItemSameComponents(ingredient, ingredients[index])) slots[s++] = first + index;
        }

        int rotation = 0;
        int reserve = AutoTradeConfigs.Trade.CRAFT_RESIDUE.getIntegerValue();
        for (int source = 0; source < menu.slots.size(); source++) {
            if (source >= first && source <= last) continue;
            ItemStack sourceStack = menu.getSlot(source).getItem();
            if (sourceStack.isEmpty() || !ItemStack.isSameItemSameComponents(sourceStack, ingredient)) continue;
            // 合成残余：0.5*P < reserve 时整堆跳过，继续遍历下一个
            if (sourceStack.getCount() < 2 * reserve) continue;

            int half = (sourceStack.getCount() + 1) / 2;
            int target = -1;
            for (int offset = 0; offset < slots.length && target < 0; offset++) {
                int slotId = slots[(rotation + offset) % slots.length];
                ItemStack gridStack = menu.getSlot(slotId).getItem();
                if (!gridStack.isEmpty() && !ItemStack.isSameItemSameComponents(gridStack, ingredient)) continue;
                if (gridStack.getCount() + half <= maxStack) {
                    target = slotId;
                    rotation = (rotation + offset + 1) % slots.length;
                }
            }
            if (target < 0) continue; // half would overflow every recipe slot: skip this stack

            // Right-click take half (a 1-count stack is taken whole).
            click(minecraft, menu, source, 1, ContainerInput.PICKUP);
            if (!ItemStack.isSameItemSameComponents(menu.getCarried(), ingredient)) {
                returnCursorToInventoryOrDrop(menu, first, last, minecraft);
                continue;
            }
            // Place the whole half onto the target slot.
            click(minecraft, menu, target, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) click(minecraft, menu, source, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) returnCursorToInventoryOrDrop(menu, first, last, minecraft);
        }
        return true;
    }

    /** Evens out each ingredient's grid slots so the recipe stays valid and balanced. */
    private static void balanceGrid(AbstractContainerMenu menu, Minecraft minecraft, int first, ItemStack[] ingredients) {
        for (int recipeIndex = 0; recipeIndex < ingredients.length; recipeIndex++) {
            ItemStack ingredient = ingredients[recipeIndex];
            if (ingredient.isEmpty() || wasHandled(ingredients, recipeIndex)) continue;

            int slotCount = 0;
            for (int index = 0; index < ingredients.length; index++) {
                if (ItemStack.isSameItemSameComponents(ingredient, ingredients[index])) slotCount++;
            }
            int[] slots = new int[slotCount];
            for (int index = 0, s = 0; index < ingredients.length; index++) {
                if (ItemStack.isSameItemSameComponents(ingredient, ingredients[index])) slots[s++] = first + index;
            }
            if (slots.length < 2) continue;

            while (true) {
                int maxIdx = 0;
                int minIdx = 0;
                int total = 0;
                for (int i = 0; i < slots.length; i++) {
                    ItemStack stack = menu.getSlot(slots[i]).getItem();
                    int count = ItemStack.isSameItemSameComponents(stack, ingredient) ? stack.getCount() : 0;
                    total += count;
                    if (count > countAt(menu, slots, ingredient, maxIdx)) maxIdx = i;
                    if (count < countAt(menu, slots, ingredient, minIdx)) minIdx = i;
                }
                if (total < slots.length) break; // cannot give every slot at least 1
                if (countAt(menu, slots, ingredient, maxIdx) - countAt(menu, slots, ingredient, minIdx) <= 1) break;

                // Move one item from the fullest slot to the emptiest slot.
                click(minecraft, menu, slots[maxIdx], 0, ContainerInput.PICKUP);
                click(minecraft, menu, slots[maxIdx], 1, ContainerInput.PICKUP);
                click(minecraft, menu, slots[minIdx], 1, ContainerInput.PICKUP);
                if (!menu.getCarried().isEmpty()) click(minecraft, menu, slots[maxIdx], 0, ContainerInput.PICKUP);
                if (!menu.getCarried().isEmpty()) return;
            }
        }
    }

    private static int countAt(AbstractContainerMenu menu, int[] slots, ItemStack ingredient, int index) {
        ItemStack stack = menu.getSlot(slots[index]).getItem();
        return ItemStack.isSameItemSameComponents(stack, ingredient) ? stack.getCount() : 0;
    }

    private static boolean wasHandled(ItemStack[] ingredients, int index) {
        for (int previous = 0; previous < index; previous++) {
            if (ItemStack.isSameItemSameComponents(ingredients[index], ingredients[previous])) return true;
        }
        return false;
    }

    private static boolean moveToInventory(AbstractContainerMenu menu, Minecraft minecraft, int source, int first, int last) {
        click(minecraft, menu, source, 0, ContainerInput.PICKUP);
        returnCursorToInventoryOrDrop(menu, first, last, minecraft);
        return menu.getCarried().isEmpty();
    }

    private static ItemStack[] copyGrid(AbstractContainerMenu menu, int first, int last) {
        ItemStack[] grid = new ItemStack[last - first + 1];
        for (int index = 0; index < grid.length; index++) {
            grid[index] = menu.getSlot(first + index).getItem().copy();
        }
        return grid;
    }

    private static boolean gridChanged(AbstractContainerMenu menu, int first, int last, ItemStack[] before) {
        if (before.length != last - first + 1) return true;
        for (int index = 0; index < before.length; index++) {
            ItemStack previous = before[index];
            ItemStack after = menu.getSlot(first + index).getItem();
            if (!ItemStack.isSameItemSameComponents(previous, after)
                    || previous.getCount() != after.getCount()) return true;
        }
        return false;
    }

    private static void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button, ContainerInput action) {
        Player player = minecraft.player;
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, action, player);
    }
}
