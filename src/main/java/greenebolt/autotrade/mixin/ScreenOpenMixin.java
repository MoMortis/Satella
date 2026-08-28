package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.AutoCraftController;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public class ScreenOpenMixin {
	@Inject(method = "onOpenScreen(Lnet/minecraft/network/packet/s2c/play/OpenScreenS2CPacket;)V", at = @At("HEAD"), cancellable = true)
	private void onOpenScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
		boolean hiddenMerchant = AutoTradeConfigs.isEnabled() && packet.getScreenHandlerType() == ScreenHandlerType.MERCHANT;
		boolean hiddenCrafting = AutoCraftController.isActive() && packet.getScreenHandlerType() == ScreenHandlerType.CRAFTING;
		if (!hiddenMerchant && !hiddenCrafting) {
			return;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		PlayerEntity player = mc.player;
		if (player == null) {
			return;
		}

		// 不打开交易 GUI：仅在本地用空 merchant 容器占位以满足 clickSlot 的本地同步检查，交易全部走网络包
		ScreenHandler handler = packet.getScreenHandlerType().create(packet.getSyncId(), player.getInventory());
		player.currentScreenHandler = handler;
		ci.cancel();
	}
}
