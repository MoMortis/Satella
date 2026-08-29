package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 容器内 Q / Ctrl+Q、光标移出界面丢弃等（THROW 类点击） */
@Mixin(ClientPlayerInteractionManager.class)
public class DropBlockClickSlotMixin {
	@Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
	private void satella$blockThrow(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
		if (DropBlock.isSlotDropBlocked(player.currentScreenHandler, slotId, actionType)) {
			ci.cancel();
		}
	}
}
