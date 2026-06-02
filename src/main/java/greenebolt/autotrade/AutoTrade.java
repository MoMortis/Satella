package greenebolt.autotrade;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AutoTrade implements ModInitializer {
	public static final String MOD_ID = "auto-trade";
	public static final String VERSION = "1.0.0";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static TradeState state = TradeState.IDLE;
	public static List<Integer> tradeOfferIndex = new ArrayList<>();
	public static List<Integer> tradeUsesLeft = new ArrayList<>();
	public static Config config;

	private int tickCounter;

	@Override
	public void onInitialize() {

		// Initialize config
		Minecraft mc = Minecraft.getInstance();
		config = new Config(mc.gameDirectory.getAbsolutePath() + File.separator + "config" + File.separator + "autotrade" + File.separator + "AutoTradeConfig.json");
		config.read();

		String KEY = "key.autotrade.trade";
		KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("autotrade"));
		KeyMapping tradekey;
		tradekey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				KEY,
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (tradekey.consumeClick()) {
				toggleAutoTrade();
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(
						ClientCommands.literal("autotrade")
								.then(ClientCommands.literal("add")
										.then(ClientCommands.argument("itemtype", StringArgumentType.string())
												.executes(AutoTrade::add)))
								.then(ClientCommands.literal("remove")
										.then(ClientCommands.argument("itemtype", StringArgumentType.string())
												.executes(AutoTrade::remove)))
								.then(ClientCommands.literal("list")
										.executes(AutoTrade::list))
				)
		);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tickCounter++;
			if (tickCounter >= 10) {
				if (client.player != null && state == TradeState.PROXIMITY_TRADE) {
					tradeWithNearbyVillager();
				}
				tickCounter = 0;
			}
		});

	}

	public void toggleAutoTrade() {
		assert Minecraft.getInstance().player != null;
		if (state == TradeState.IDLE) {
            Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Autotrading is on..."));
			state = TradeState.INSTANT_TRADE;
		}
		else if (state == TradeState.INSTANT_TRADE){
			Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Proximity trading is on..."));
			state = TradeState.PROXIMITY_TRADE;
		} else {
			Minecraft.getInstance().player.sendOverlayMessage(Component.literal("Autotrading is off..."));
			state = TradeState.IDLE;
		}
	}

	public void tradeWithNearbyVillager() {

		Minecraft mc = Minecraft.getInstance();
		assert mc.level != null;
		assert mc.player != null;

		double closestDistance = Double.POSITIVE_INFINITY;
		Villager closestVillager = null;

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof Villager && mc.player.distanceTo(entity) < mc.player.entityInteractionRange() && mc.player.distanceTo(entity) < closestDistance) {
				closestVillager = (Villager) entity;
			}
		}
		if (closestVillager == null) return;

		// Max interaction range is 4
		assert mc.gameMode != null;

		// Send Packet so that mixin can handle results
		InteractionResult result = null;
		result = mc.gameMode.interact(mc.player, closestVillager, new EntityHitResult(closestVillager), InteractionHand.MAIN_HAND);
		mc.player.swing(InteractionHand.MAIN_HAND, true);
		mc.player.connection
				.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
		mc.player.connection
				.send(new ServerboundInteractPacket(closestVillager.getId(), InteractionHand.MAIN_HAND, mc.player.position(), false));


		if(result != InteractionResult.SUCCESS) {
			mc.player.sendSystemMessage(Component.literal("Failed to send packet..."));
		}

	}

	private static int add(CommandContext<FabricClientCommandSource> context) {
		assert Minecraft.getInstance().player != null;
		String itemToAdd = StringArgumentType.getString(context, "itemtype");
		Identifier itemId = Identifier.parse("minecraft:" + itemToAdd);
		if (BuiltInRegistries.ITEM.containsKey(itemId)) {
			List<String> items = new ArrayList<>(Arrays.stream(Config.targetItems).toList());
			items.add(itemToAdd);
			Config.targetItems = items.toArray(new String[0]);
			Config.save();
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("Added item: " + itemToAdd).withStyle(ChatFormatting.GREEN));
		} else Minecraft.getInstance().player.sendSystemMessage(Component.literal(itemToAdd + " is not a valid item...").withStyle(ChatFormatting.RED));
		return 1;
	}
	private static int remove(CommandContext<FabricClientCommandSource> context) {
		assert Minecraft.getInstance().player != null;
		String itemToRemove = StringArgumentType.getString(context, "itemtype");
		List<String> items = new ArrayList<>(Arrays.stream(Config.targetItems).toList());
		if (items.remove(itemToRemove)) {
			Config.targetItems = items.toArray(new String[0]);
			Config.save();
			Minecraft.getInstance().player.sendSystemMessage(Component.literal("Removed item: " + itemToRemove).withStyle(ChatFormatting.GREEN));
		} else Minecraft.getInstance().player.sendSystemMessage(Component.literal(itemToRemove + " is not in the list of AutoTrade items...").withStyle(ChatFormatting.RED));
		return 1;
	}
	private static int list(CommandContext<FabricClientCommandSource> context) {
		assert Minecraft.getInstance().player != null;
		Minecraft.getInstance().player.sendSystemMessage(Component.literal("AutoTrade items: " + String.join(", ", Config.targetItems)).withStyle(ChatFormatting.GREEN));
		return 1;
	}
}