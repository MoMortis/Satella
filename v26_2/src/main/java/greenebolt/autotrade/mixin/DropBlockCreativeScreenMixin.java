package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 创造模式物品栏内丢弃（THROW 点击在创造界面走独立路径） */
@Mixin(value = CreativeModeInventoryScreen.class, remap = false)
public class DropBlockCreativeScreenMixin {
    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IIILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void satella$blockThrow(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (input != ContainerInput.THROW || DropBlock.suppressInternal) {
            return;
        }
        ItemStack stack;
        if (slot != null && slot.hasItem()) {
            stack = slot.getItem();
        } else {
            // menu 字段在父类 AbstractContainerScreen 上，直接用 getter，避免 @Shadow 解析不到
            stack = ((AbstractContainerScreen<?>) (Object) this).getMenu().getCarried();
        }
        if (!stack.isEmpty() && DropBlock.isBlockedItem(stack.getItem())) {
            ci.cancel();
        }
    }
}
