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
            ItemStack outputBefore = menu.getSlot(0).getItem().copy();
            if (!ItemStack.isSameItemSameComponents(outputBefore, result)) return crafted;
            DropBlock.suppressInternal = true;
            try {
                // 与 Item Scroller 的 dropStack 相同：button=1，尽可能取出输出槽中的整组结果。
                click(minecraft, menu, 0, 1, ContainerInput.THROW);
            } finally {
                DropBlock.suppressInternal = false;
            }
            crafted = true;
            ItemStack outputAfter = menu.getSlot(0).getItem();
            // 输出槽没有变化时停止，避免服务器未接受点击导致死循环。
            if (ItemStack.matches(outputBefore, outputAfter)) return crafted;
        }
        return crafted;
    }

    private static boolean prepare(AbstractContainerMenu menu, Minecraft minecraft, int first, int last, ItemStack[] ingredients) {
        if (!clearCursor(menu, minecraft, first, last)) return false;
        for (int index = 0; index < ingredients.length; index++) {
            int slotId = first + index;
            ItemStack expected = ingredients[index];
            Slot slot = menu.getSlot(slotId);
            ItemStack actual = slot.getItem();
            if (!actual.isEmpty() && (expected.isEmpty() || !ItemStack.isSameItemSameComponents(actual, expected))) {
                click(minecraft, menu, slotId, 0, ContainerInput.THROW);
            }
        }
        for (int index = 0; index < ingredients.length; index++) {
            ItemStack expected = ingredients[index];
            if (expected.isEmpty() || wasHandled(ingredients, index)) continue;
            if (!fillIngredient(menu, minecraft, first, last, ingredients, expected)) return false;
        }
        balanceGrid(menu, minecraft, first, ingredients);
        for (int index = ingredients.length; first + index <= last; index++) {
            if (!menu.getSlot(first + index).getItem().isEmpty() && !moveToInventory(menu, minecraft, first + index, first, last)) return false;
        }
        return menu.getCarried().isEmpty();
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
                return clearCursor(menu, minecraft, first, last);
            }
            // Place the whole half onto the target slot.
            click(minecraft, menu, target, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) click(minecraft, menu, source, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty()) return clearCursor(menu, minecraft, first, last);
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
        return clearCursor(menu, minecraft, first, last);
    }

    private static boolean clearCursor(AbstractContainerMenu menu, Minecraft minecraft, int first, int last) {
        if (menu.getCarried().isEmpty()) return true;
        // 清理光标残留时直接丢出背包；是否允许由“拦截目标物品丢弃”统一决定。
        click(minecraft, menu, -999, 0, ContainerInput.THROW);
        return menu.getCarried().isEmpty();
    }

    private static void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button, ContainerInput action) {
        Player player = minecraft.player;
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, action, player);
    }
}
