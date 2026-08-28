package greenebolt.autotrade.mixin.itemscroller;

import greenebolt.autotrade.StackNormalizer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "fi.dy.masa.itemscroller.util.InventoryUtils", remap = false)
public abstract class InventoryUtilsMixin {
    @Inject(method = "areStacksEqual", at = @At("HEAD"), cancellable = true, remap = false)
    private static void autoTrade$compareNormalized(ItemStack first, ItemStack second,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (StackNormalizer.sameIdentity(first, second)) {
            cir.setReturnValue(true);
        }
    }

}
