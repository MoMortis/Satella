package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTrade;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class InteractionMixin {
	@Inject(method = "interactEntity", at = @At("HEAD"))
	private void onInteractEntity(PlayerEntity player, Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
		AutoTrade.onInteractEntity(entity);
	}
}
