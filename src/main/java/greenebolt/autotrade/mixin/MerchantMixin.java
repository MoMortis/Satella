package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.TradeExecutor;
import io.netty.channel.ChannelHandlerContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.screen.ScreenHandlerType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ClientConnection.class)
public class MerchantMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("RETURN"))
	private void onChannelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo ci) {
		if (!(packet instanceof OpenScreenS2CPacket) && !(packet instanceof SetTradeOffersS2CPacket)) {
			return;
		}

		// channelRead0 运行在 Netty I/O 线程，这里把全部处理切回主线程（渲染线程），
		// 避免跨线程读写 Minecraft 状态与共享列表导致的竞争
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.isOnThread()) {
			processPacket(packet);
		} else {
			mc.execute(() -> processPacket(packet));
		}
	}

	private void processPacket(Packet<?> packet) {
		if (!AutoTradeConfigs.isEnabled()) {
			return;
		}

		if (packet instanceof OpenScreenS2CPacket openScreenS2CPacket) {
			if (openScreenS2CPacket.getScreenHandlerType() == ScreenHandlerType.MERCHANT) {
				// 每次打开交易界面都清空缓存，只使用本次 SetTradeOffersS2CPacket 填充的新交易列表
				AutoTrade.tradeOfferIndex.clear();
				AutoTrade.tradeUsesLeft.clear();
				AutoTrade.tradeRefillCount.clear();
			}
		} else if (packet instanceof SetTradeOffersS2CPacket setTradeOffersS2CPacket) {
			TradeExecutor.handleTradeOffers(setTradeOffersS2CPacket);
		}
	}
}
