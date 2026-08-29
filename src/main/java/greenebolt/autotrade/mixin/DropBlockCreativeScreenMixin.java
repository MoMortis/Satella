package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 创造模式物品栏内丢弃（THROW 点击在创造界面走独立路径） */
@Mixin(CreativeInventoryScreen.class)
public class DropBlockCreativeScreenMixin {
	@Shadow
	protected ScreenHandler handler;

	@Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IIILnet/minecraft/screen/slot/SlotActionType;)V",
			at = @At("HEAD"), cancellable = true)
	private void satella$blockThrow(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
		if (actionType != SlotActionType.THROW || DropBlock.suppressInternal) {
			return;
		}
		ItemStack stack = slot != null && slot.hasStack() ? slot.getStack() : this.handler.getCursorStack();
		if (!stack.isEmpty() && DropBlock.isBlockedItem(stack.getItem())) {
			ci.cancel();
		}
	}
}
