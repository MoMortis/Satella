package greenebolt.autotrade;

import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易执行逻辑（普通类，不属于 mixin，避免 mixin 把非 private 方法注入目标类）。
 * 由 MerchantMixin（收到交易包）与 AutoTrade（每间隔 tick）调用。
 */
public class TradeExecutor {
    private TradeExecutor() {
    }

    /** 收到 SetTradeOffersS2CPacket：构建匹配交易快照；自动模式只激活会话，单次模式一次性买完 */
    public static void handleTradeOffers(SetTradeOffersS2CPacket setTradeOffersS2CPacket) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            return;
        }

        AutoTrade.tradeOfferIndex.clear();
        AutoTrade.tradeUsesLeft.clear();
        AutoTrade.tradeRefillCount.clear();

        int villagerId = AutoTrade.getCurrentVillagerId();
        int targetItemCount = 0;
        List<String> errors = new ArrayList<>();
        TradeEntry entry = TradeEntry.build(
                AutoTradeConfigs.Trade.INPUT_ITEM_1.getStringValue(),
                AutoTradeConfigs.Trade.INPUT_ITEM_2.getStringValue(),
                AutoTradeConfigs.Trade.OUTPUT_ITEM.getStringValue());
        List<TradeOffer> offers = setTradeOffersS2CPacket.getOffers();
        for (int idx = 0; idx < offers.size(); idx++) {
            TradeOffer tradeOffer = offers.get(idx);
            if (entry != null && entry.matches(tradeOffer)) {
                targetItemCount++;
                String itemName = tradeOffer.getSellItem().getName().getString();
                if (tradeOffer.getMaxUses() > -1 && tradeOffer.getUses() >= tradeOffer.getMaxUses()) {
                    errors.add(itemName + " 的库存已售罄");
                    continue;
                }
                double canBuy = Math.min(maxHowManyUses(player, tradeOffer.getFirstBuyItem()),
                        maxHowManyUses(player, tradeOffer.getSecondBuyItem().orElse(null)));
                double remaining = tradeOffer.getMaxUses() == -1 ? canBuy
                        : Math.min(canBuy, tradeOffer.getMaxUses() - tradeOffer.getUses());
                int available = (int) Math.min(remaining, 1000000.0);
                if (available <= 0) {
                    errors.add("背包中的交易物品不足");
                    continue;
                }
                AutoTrade.tradeOfferIndex.add(idx);
                AutoTrade.tradeUsesLeft.add(available);
                AutoTrade.tradeRefillCount.add(refillCountPerTrade(tradeOffer));
            }
        }

        if (targetItemCount == 0) {
            AutoTrade.onVillagerNoTrades(villagerId);
        } else if (AutoTrade.tradeUsesLeft.isEmpty()) {
            AutoTrade.onVillagerBoughtOut(villagerId, String.join("，", errors));
        } else {
            AutoTrade.onVillagerBuying(villagerId);
            InfoUtils.sendVanillaMessage(Text.literal("正在购买匹配的交易").formatted(Formatting.GREEN)
                    .append(Text.literal(errors.isEmpty() ? "" : "（" + String.join("，", errors) + "）").formatted(Formatting.RED)));
        }
        int syncId = setTradeOffersS2CPacket.getSyncId();
        List<Integer> indices = new ArrayList<>(AutoTrade.tradeOfferIndex);
        List<Integer> uses = new ArrayList<>(AutoTrade.tradeUsesLeft);
        List<Integer> refills = new ArrayList<>(AutoTrade.tradeRefillCount);
        mc.execute(() -> doPurchases(mc, syncId, indices, uses, refills));
    }

    /** 每次打开交易界面后成交 TRADES_PER_SESSION 次并关闭界面 */
    private static void doPurchases(MinecraftClient mc, int syncId, List<Integer> indices, List<Integer> uses, List<Integer> refills) {
        if (!AutoTradeConfigs.isEnabled()) {
            return;
        }
        if (!validSession(mc, syncId)) {
            return;
        }
        if (indices.isEmpty()) {
            closeMerchantScreen(mc.getNetworkHandler(), syncId, mc);
            return;
        }
        int tradesPerSession = Math.max(1, AutoTradeConfigs.Trade.TRADES_PER_SESSION.getIntegerValue());
        performTrades(mc, syncId, indices, uses, refills, tradesPerSession);
        closeMerchantScreen(mc.getNetworkHandler(), syncId, mc);
    }

    /** 只执行成交（不关闭界面）；maxTrades 限制本次最多成交次数 */
    private static void performTrades(MinecraftClient mc, int syncId, List<Integer> indices, List<Integer> uses, List<Integer> refills, int maxTrades) {
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (mc.player == null || networkHandler == null || mc.interactionManager == null) {
            return;
        }
        boolean dropOutputs = AutoTradeConfigs.Trade.DROP_OUTPUTS.getBooleanValue();
        int done = 0;

        outer:
        for (int i = 0; i < indices.size(); i++) {
            int offerIndex = indices.get(i);
            int availableMaxTrades = uses.get(i);
            int perRefill = refills.get(i);
            int sinceRefill = 0;

            // 切换到当前交易槽（服务端 autofill 填满输入槽）
            networkHandler.sendPacket(new SelectMerchantTradeC2SPacket(offerIndex));

            for (int c = 0; c < availableMaxTrades; c++) {
                if (done >= maxTrades) {
                    break outer;
                }
                done++;

                // 输入槽每成交 perRefill 次就会耗尽，需重新选中交易触发服务端重新 autofill 填满
                if (sinceRefill >= perRefill) {
                    networkHandler.sendPacket(new SelectMerchantTradeC2SPacket(offerIndex));
                    sinceRefill = 0;
                }
                sinceRefill++;

                // 左键点击交易输出槽（PICKUP）：拿起输出并成交 1 次（无链式）
                mc.interactionManager.clickSlot(syncId, 2, 0, SlotActionType.PICKUP, mc.player);

                if (dropOutputs) {
                    // 点击界面外左键，丢弃光标上拿到的输出物品
                    mc.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, mc.player);
                } else {
                    // 放回背包的空槽或同类槽
                    mc.interactionManager.clickSlot(syncId, findInventorySlotToPlace(mc), 0, SlotActionType.PICKUP, mc.player);
                }
            }
        }
    }

    // 校验当前占位 handler 是否仍是对应 syncId 的 merchant 容器
    private static boolean validSession(MinecraftClient mc, int syncId) {
        if (mc.player == null || mc.getNetworkHandler() == null || mc.interactionManager == null) {
            return false;
        }
        return mc.player.currentScreenHandler instanceof MerchantScreenHandler
                && mc.player.currentScreenHandler.syncId == syncId;
    }

    // 每次重新选中交易后，服务端 autofill 会把输入槽填满到物品最大堆叠，
    // 能支撑 floor(maxCount / cost) 次成交；返回该值（至少 1）
    private static int refillCountPerTrade(TradeOffer offer) {
        int first = refillCountFor(offer.getFirstBuyItem());
        int second = offer.getSecondBuyItem().map(TradeExecutor::refillCountFor).orElse(Integer.MAX_VALUE);
        return Math.max(1, Math.min(first, second));
    }

    private static int refillCountFor(TradedItem buy) {
        if (buy == null) {
            return Integer.MAX_VALUE;
        }
        int cost = Math.max(1, buy.count());
        return Math.max(1, (int) Math.floor((double) buy.itemStack().getMaxCount() / cost));
    }

    // 背包里的该输入物品一共还能买多少次
    private static double maxHowManyUses(PlayerEntity player, TradedItem buy) {
        if (buy == null) {
            return Double.MAX_VALUE;
        }
        int cost = Math.max(1, buy.count());
        return Math.floor((double) numberOfItems(player, buy.itemStack().getItem()) / cost);
    }

    // 挑选一个用于放回光标物品的背包槽位（merchant 容器槽位映射：3..29 玩家主背包，30..38 快捷栏）。
    // 优先同物品可合并的槽，其次空槽；若背包全满则兜底第一个背包槽（可能交换滞留，见下轮清理）。
    private static int findInventorySlotToPlace(MinecraftClient mc) {
        PlayerInventory inv = mc.player.getInventory();
        ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();

        for (int mainIndex = 0; mainIndex < inv.size(); mainIndex++) {
            ItemStack stack = inv.getStack(mainIndex);
            if (!stack.isEmpty() && !cursor.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, cursor)) {
                return mainIndex < 27 ? 3 + mainIndex : 30 + (mainIndex - 27);
            }
        }
        for (int mainIndex = 0; mainIndex < inv.size(); mainIndex++) {
            if (inv.getStack(mainIndex).isEmpty()) {
                return mainIndex < 27 ? 3 + mainIndex : 30 + (mainIndex - 27);
            }
        }
        return 3;
    }

    public static int numberOfItems(PlayerEntity player, Item targetItem) {
        int count = 0;

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (stack.getItem() == targetItem) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void closeMerchantScreen(ClientPlayNetworkHandler networkHandler, int containerID, MinecraftClient mc) {
        // 只有当前占位 handler 确实是对应本次容器的 merchant 时才关闭并重置，
        // 否则可能把新村民刚设置好的占位 handler 一起清掉（导致其交易静默失效）
        if (!(mc.player.currentScreenHandler instanceof MerchantScreenHandler)
                || mc.player.currentScreenHandler.syncId != containerID) {
            return;
        }

        networkHandler.sendPacket(new CloseHandledScreenC2SPacket(containerID));

        // 不能调用 mc.player.closeHandledScreen()：它内部会执行 closeScreen() -> setScreen(null)，
        // 打开聊天栏交易时会强制关闭聊天栏。这里只复刻 vanilla PlayerEntity.closeHandledScreen()
        // 的容器部分，不动当前屏幕。
        mc.player.currentScreenHandler.onClosed(mc.player);
        mc.player.currentScreenHandler = mc.player.playerScreenHandler;

        AutoTrade.tradeOfferIndex.clear();
        AutoTrade.tradeUsesLeft.clear();
        AutoTrade.tradeRefillCount.clear();
    }
}
