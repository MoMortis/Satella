package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 手持 Q / Ctrl+Q 丢弃 */
@Mixin(ClientPlayerEntity.class)
public class DropBlockPlayerMixin {
	@Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
	private void satella$blockDrop(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
		ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
		if (DropBlock.isHandDropBlocked(self.getMainHandStack())) {
			cir.setReturnValue(false);
		}
	}
}
