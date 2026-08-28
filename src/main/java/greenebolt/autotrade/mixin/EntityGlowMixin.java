package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTrade;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityGlowMixin {
	@Inject(method = "isGlowing", at = @At("RETURN"), cancellable = true)
	private void onIsGlowing(CallbackInfoReturnable<Boolean> cir) {
		if (AutoTrade.isHighlighted((Entity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
