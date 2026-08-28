package greenebolt.autotrade.mixin.ipn;

import greenebolt.autotrade.StackNormalizer;
import greenebolt.autotrade.compat.ItemTypeBridgeAccess;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.MergedComponentMap;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.anti_ad.mc.ipnext.item.ItemType", remap = false)
public abstract class ItemTypeMixin implements ItemTypeBridgeAccess {
    @Shadow(remap = false)
    public abstract Item getItem();

    @Shadow(remap = false)
    public abstract MergedComponentMap getTag();

    @Inject(method = "equals", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoTrade$equals(Object other, CallbackInfoReturnable<Boolean> cir) {
        if (!StackNormalizer.isCompatEnabled() || !(other instanceof ItemTypeBridgeAccess otherAccess)) {
            return;
        }

        Item item = autoTrade$item();
        Item otherItem = otherAccess.autoTrade$item();
        if (!StackNormalizer.isShulkerBoxItem(item) && !StackNormalizer.isShulkerBoxItem(otherItem)) {
            return;
        }

        cir.setReturnValue(item == otherItem
                && StackNormalizer.normalizedComponentsEqual(
                        autoTrade$components(), otherAccess.autoTrade$components(), true));
    }

    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoTrade$hashCode(CallbackInfoReturnable<Integer> cir) {
        if (!StackNormalizer.isCompatEnabled()) {
            return;
        }

        Item item = autoTrade$item();
        if (StackNormalizer.isShulkerBoxItem(item)) {
            cir.setReturnValue(31 * item.hashCode()
                    + StackNormalizer.normalizedComponentsHash(autoTrade$components(), true));
        }
    }

    @Override
    public Item autoTrade$item() {
        return getItem();
    }

    @Override
    public ComponentMap autoTrade$components() {
        return getTag();
    }
}
