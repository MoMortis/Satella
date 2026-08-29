package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 创造模式物品栏内丢弃（THROW 点击在创造界面走独立路径） */
@Mixin(value = CreativeModeInventoryScreen.class, remap = false)
public class DropBlockCreativeScreenMixin {
    @Shadow
    protected AbstractContainerMenu menu;

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IIILnet/minecraft/world/inventory/ContainerInput;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void satella$blockThrow(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (input != ContainerInput.THROW || DropBlock.suppressInternal) {
            return;
        }
        ItemStack stack = slot != null && slot.hasItem() ? slot.getItem() : this.menu.getCarried();
        if (!stack.isEmpty() && DropBlock.isBlockedItem(stack.getItem())) {
            ci.cancel();
        }
    }
}
