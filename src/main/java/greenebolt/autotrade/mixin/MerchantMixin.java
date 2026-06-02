package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.Config;
import greenebolt.autotrade.TradeState;
import io.netty.channel.ChannelHandlerContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Environment(EnvType.CLIENT)
@Mixin(Connection.class)
public class MerchantMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("RETURN"))
	private void onChannelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo ci) {

		if (packet instanceof ClientboundOpenScreenPacket openScreenS2CPacket) {

			if (openScreenS2CPacket.getType() == MenuType.MERCHANT && !(AutoTrade.state == TradeState.IDLE)) {
				Minecraft mc = Minecraft.getInstance();
				ClientPacketListener networkHandler = mc.getConnection();

				// Buy goods
				mc.execute(() -> {
					assert Minecraft.getInstance().player != null;
					assert networkHandler != null;

					if (AutoTrade.tradeOfferIndex.isEmpty()) {
                        closeMerchantScreen(networkHandler, openScreenS2CPacket.getContainerId(), mc);
						return;
					}

					for (int i = 0; i < AutoTrade.tradeOfferIndex.size(); i++) {
                        assert mc.player != null;

						int availiableMaxTrades = AutoTrade.tradeUsesLeft.get(i);

						for (int c = 0; c < availiableMaxTrades; c++) {
							networkHandler.send(new ServerboundSelectTradePacket(AutoTrade.tradeOfferIndex.get(i)));

							mc.gameMode.handleContainerInput(
									openScreenS2CPacket.getContainerId(),
									2,
									0,
									ContainerInput.QUICK_MOVE,
									mc.player
							);
						}
					}

					closeMerchantScreen(networkHandler, openScreenS2CPacket.getContainerId(), mc);
				});
			}
		} else if (packet instanceof ClientboundMerchantOffersPacket setTradeOffersS2CPacket && AutoTrade.state != TradeState.IDLE) {

			int targetItemCount = 0;
			String villagerErrorMsg = ""; //public static List<Integer> tradeOfferIndex = new ArrayList<>();

			for (MerchantOffer tradeOffer : setTradeOffersS2CPacket.getOffers()) {

				if (Arrays.asList(Config.targetItems).contains(tradeOffer.getCostA().getItem().getDescriptionId().replace("item.minecraft.", "").replace("block.minecraft.", ""))) {
					targetItemCount += 1;
					String itemName = tradeOffer.getCostA().getItemName().getString();
					if (tradeOffer.isOutOfStock()) {
						villagerErrorMsg = addToVillagerErrorMessage(villagerErrorMsg, " Villager is sold out of ", itemName);
						//Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Villager is sold out...").withStyle(ChatFormatting.RED));
						continue;
					}

					// Store trade offer data
					int offerCostCount = Math.max(1, tradeOffer.getItemCostA().count() + tradeOffer.getSpecialPriceDiff());
					//Minecraft.getInstance().player.sendSystemMessage(Component.literal("VillagerCost: " + offerCostCount));
					double numUsesPerClick = Math.floor((double) tradeOffer.getItemCostA().itemStack().getMaxStackSize() / offerCostCount);
					double howManyUsesYouCanBuy = Math.floor((double) numberOfItems(Minecraft.getInstance().player, tradeOffer.getItemCostA().itemStack().getItem()) / offerCostCount);

					//Minecraft.getInstance().player.sendSystemMessage(Component.literal("You can buy: " + howManyUsesYouCanBuy + " " + itemName));

					int availableMaxTrades;
					if (howManyUsesYouCanBuy < tradeOffer.getMaxUses() - tradeOffer.getUses()) {
						availableMaxTrades = (int) Math.ceil(howManyUsesYouCanBuy / numUsesPerClick);

						if (availableMaxTrades != 0)
							villagerErrorMsg = addToVillagerErrorMessage(villagerErrorMsg, " You are out of ", itemName);//Minecraft.getInstance().player.sendOverlayMessage(Component.literal("You are out of " + tradeOffer.getCostA().getItemName().getString() + "s...").withStyle(ChatFormatting.RED));
						else {
							villagerErrorMsg = addToVillagerErrorMessage(villagerErrorMsg, " You don't have enough ", itemName);//Minecraft.getInstance().player.sendOverlayMessage(Component.literal("You don't have enough " + tradeOffer.getCostA().getItemName().getString() + "s...").withStyle(ChatFormatting.GREEN));
							continue;
						}
					}
					else {
						availableMaxTrades = (int) Math.ceil(tradeOffer.getMaxUses() - tradeOffer.getUses() / numUsesPerClick);
						//villagerErrorMsg = addToVillagerErrorMessage(villagerErrorMsg, " Buying out ", itemName);
						//Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Buying!").withStyle(ChatFormatting.GREEN));
					}

					//AutoTrade.LOGGER.info(tradeOffer.getItemCostA().itemStack().getItemName().getString() + " Inventory Count: " + numberOfItems(Minecraft.getInstance().player, tradeOffer.getItemCostA().itemStack().getItem()) + " Trade Uses Left: " + availableMaxTrades);

					AutoTrade.tradeOfferIndex.add(setTradeOffersS2CPacket.getOffers().indexOf(tradeOffer));
					AutoTrade.tradeUsesLeft.add(availableMaxTrades);
				}
			}

			if (targetItemCount == 0) {

				Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Villager does not contain valid trades...").withStyle(ChatFormatting.RED));
				return;
			}
			if (AutoTrade.tradeUsesLeft.size() == 0) {
				// No Valid Trades
				Minecraft.getInstance().player.sendOverlayMessage(Component.literal(villagerErrorMsg).withStyle(ChatFormatting.RED));
			} else {
				// Has valid Trades
				Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Buying...").withStyle(ChatFormatting.GREEN)
						.append(Component.literal(" " + villagerErrorMsg).withStyle(ChatFormatting.RED)));
			}

		}
	}
	public String addToVillagerErrorMessage(String errorMessage, String error, String itemName) {
		if (errorMessage.contains(error)) {
			errorMessage = errorMessage.substring(0, errorMessage.indexOf(error) + error.length()) + itemName + " and " + errorMessage.substring(errorMessage.indexOf(error) + error.length());
		}
		 else
			errorMessage = errorMessage + error + itemName;

		return errorMessage;
	}

	public int numberOfItems(Player player, Item targetItem) {
		int count = 0;

		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);

			if (stack.getItem() == targetItem) {
				count += stack.getCount();
			}
		}
		//Minecraft.getInstance().player.sendSystemMessage(Component.literal("You have: " + count + " " + targetItem.getDefaultInstance().getItemName().getString()));
		return count;
	}

	public void closeMerchantScreen(ClientPacketListener networkHandler, int containerID, Minecraft mc) {
		networkHandler.send(new ServerboundContainerClosePacket(containerID));
		mc.player.closeContainer();

		AutoTrade.tradeOfferIndex.clear();
		AutoTrade.tradeUsesLeft.clear();
	}
}