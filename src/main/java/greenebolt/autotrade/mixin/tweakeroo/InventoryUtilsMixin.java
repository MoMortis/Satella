package greenebolt.autotrade.mixin.tweakeroo;

import greenebolt.autotrade.StackNormalizer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "fi.dy.masa.tweakeroo.util.InventoryUtils", remap = false)
public abstract class InventoryUtilsMixin {
    @Redirect(
            method = {"preRestockHand", "findSlotWithItem"},
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/util/InventoryUtils;areStacksEqualIgnoreDurability(Lnet/minecraft/class_1799;Lnet/minecraft/class_1799;)Z",
                    remap = false),
            require = 0,
            remap = false)
    private static boolean autoTrade$compareRestockStacks(ItemStack stack, ItemStack reference) {
        if (StackNormalizer.sameIdentity(stack, reference)) {
            return true;
        }
        return areStacksEqualIgnoreDurability(stack, reference);
    }

    private static boolean areStacksEqualIgnoreDurability(ItemStack first, ItemStack second) {
        if (first == null || second == null) {
            return false;
        }
        ItemStack firstCopy = first.copy();
        ItemStack secondCopy = second.copy();
        firstCopy.setCount(1);
        secondCopy.setCount(1);
        if (firstCopy.isDamaged() && firstCopy.isDamageable()) {
            firstCopy.setDamage(0);
        }
        if (secondCopy.isDamaged() && secondCopy.isDamageable()) {
            secondCopy.setDamage(0);
        }
        return ItemStack.areEqual(firstCopy, secondCopy);
    }
}
