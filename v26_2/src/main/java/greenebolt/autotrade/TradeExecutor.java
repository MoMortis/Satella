package greenebolt.autotrade;

import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.ArrayList;
import java.util.List;

public final class TradeExecutor {
    private TradeExecutor() {}

    public static void handleOffers(ClientboundMerchantOffersPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        TradeEntry target = TradeEntry.build(AutoTradeConfigs.Trade.INPUT_ITEM_1.getStringValue(),
                AutoTradeConfigs.Trade.INPUT_ITEM_2.getStringValue(), AutoTradeConfigs.Trade.OUTPUT_ITEM.getStringValue());
        List<Integer> indices = new ArrayList<>();
        List<Integer> available = new ArrayList<>();
        for (int index = 0; index < packet.getOffers().size(); index++) {
            MerchantOffer offer = packet.getOffers().get(index);
            if (target == null || !target.matches(offer) || offer.isOutOfStock()) continue;
            int uses = Math.min(remainingUses(minecraft.player.getInventory(), offer.getItemCostA()),
                    offer.getItemCostB().map(cost -> remainingUses(minecraft.player.getInventory(), cost)).orElse(Integer.MAX_VALUE));
            uses = Math.min(uses, offer.getMaxUses() - offer.getUses());
            if (uses > 0) {
                indices.add(index);
                available.add(uses);
            }
        }
        if (indices.isEmpty()) {
            InfoUtils.sendVanillaMessage(Component.literal("没有可购买的匹配交易或输入物品不足").withStyle(ChatFormatting.YELLOW));
            close(minecraft, packet.getContainerId());
            return;
        }
        minecraft.execute(() -> buy(minecraft, packet.getContainerId(), indices, available));
    }

    private static void buy(Minecraft minecraft, int containerId, List<Integer> indices, List<Integer> available) {
        if (!AutoTradeConfigs.isEnabled() || minecraft.player == null || minecraft.gameMode == null
                || !(minecraft.player.containerMenu instanceof MerchantMenu menu) || menu.containerId != containerId) return;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) return;
        int limit = AutoTradeConfigs.Trade.TRADES_PER_SESSION.getIntegerValue();
        int completed = 0;
        for (int i = 0; i < indices.size() && completed < limit; i++) {
            connection.getConnection().send(new ServerboundSelectTradePacket(indices.get(i)));
            for (int count = 0; count < available.get(i) && completed < limit; count++) {
                minecraft.gameMode.handleContainerInput(containerId, 2, 0, ContainerInput.PICKUP, minecraft.player);
                if (AutoTradeConfigs.Trade.DROP_OUTPUTS.getBooleanValue()) {
                    minecraft.gameMode.handleContainerInput(containerId, -999, 0, ContainerInput.PICKUP, minecraft.player);
                } else {
                    minecraft.gameMode.handleContainerInput(containerId, findDestination(menu.getCarried(), minecraft.player.getInventory()), 0,
                            ContainerInput.PICKUP, minecraft.player);
                }
                completed++;
            }
        }
        close(minecraft, containerId);
    }

    private static int remainingUses(Inventory inventory, ItemCost cost) {
        int count = 0;
        Item target = cost.item().value();
        for (ItemStack stack : inventory.getNonEquipmentItems()) if (stack.getItem() == target) count += stack.getCount();
        return count / Math.max(1, cost.count());
    }

    private static int findDestination(ItemStack carried, Inventory inventory) {
        List<ItemStack> stacks = inventory.getNonEquipmentItems();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, carried) && stack.getCount() < stack.getMaxStackSize()) return i < 27 ? i + 3 : i + 3;
        }
        for (int i = 0; i < stacks.size(); i++) if (stacks.get(i).isEmpty()) return i < 27 ? i + 3 : i + 3;
        return 3;
    }

    public static void close(Minecraft minecraft, int containerId) {
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof MerchantMenu) || minecraft.player.containerMenu.containerId != containerId) return;
        ClientPacketListener connection = minecraft.getConnection();
        if (connection != null) connection.getConnection().send(new ServerboundContainerClosePacket(containerId));
        minecraft.player.containerMenu.removed(minecraft.player);
        minecraft.player.containerMenu = minecraft.player.inventoryMenu;
    }
}
