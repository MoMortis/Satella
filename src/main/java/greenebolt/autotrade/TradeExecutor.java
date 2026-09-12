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
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradedItem;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 交易执行逻辑（普通类，不属于 mixin，避免 mixin 把非 private 方法注入目标类）。
 * 由 MerchantMixin（收到交易包）与 AutoTrade（每间隔 tick）调用。
 */
public class TradeExecutor {
    private TradeExecutor() {
    }

    // 每 gt 最多执行一轮成交的去重标记
    private static int lastPurchaseTickId = -1;
    // 最近一次 SetTradeOffers 包的原始交易列表：每交易间隔据此重新检查背包
    private static List<TradeOffer> cachedOffers = List.of();

    /** 打开新容器时清空缓存的交易列表（由 MerchantMixin 调用） */
    public static void resetCachedOffers() {
        cachedOffers = List.of();
    }

    /** 收到 SetTradeOffersS2CPacket：构建匹配交易快照；自动模式只激活会话，单次模式一次性买完 */
    public static void handleTradeOffers(SetTradeOffersS2CPacket setTradeOffersS2CPacket) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.player;
        if (player == null) {
            return;
        }

        cachedOffers = List.copyOf(setTradeOffersS2CPacket.getOffers());
        UUID villagerUuid = AutoTrade.getCurrentVillagerUuid();
        List<String> errors = new ArrayList<>();
        int targetItemCount = refreshBuyLists(player, cachedOffers, errors);

        if (targetItemCount == 0) {
            AutoTrade.onVillagerNoTrades(villagerUuid);
        } else if (AutoTrade.tradeUsesLeft.isEmpty()) {
            AutoTrade.onVillagerBoughtOut(villagerUuid, String.join("，", errors));
        } else {
            AutoTrade.onVillagerBuying(villagerUuid);
            InfoUtils.sendVanillaMessage(Text.literal("正在购买匹配的交易").formatted(Formatting.GREEN)
                    .append(Text.literal(errors.isEmpty() ? "" : "（" + String.join("，", errors) + "）").formatted(Formatting.RED)));
        }
        int syncId = setTradeOffersS2CPacket.getSyncId();
        List<Integer> indices = new ArrayList<>(AutoTrade.tradeOfferIndex);
        List<Integer> uses = new ArrayList<>(AutoTrade.tradeUsesLeft);
        List<Integer> refills = new ArrayList<>(AutoTrade.tradeRefillCount);
        mc.execute(() -> doPurchases(mc, syncId, indices, uses, refills));
    }

    /** 按当前背包重建匹配交易、可用次数与续填周期；返回匹配的交易条数（含已售罄的） */
    private static int refreshBuyLists(PlayerEntity player, List<TradeOffer> offers, List<String> errors) {
        AutoTrade.tradeOfferIndex.clear();
        AutoTrade.tradeUsesLeft.clear();
        AutoTrade.tradeRefillCount.clear();
        TradeEntry entry = TradeEntry.build(
                AutoTradeConfigs.Trade.INPUT_ITEM_1.getStringValue(),
                AutoTradeConfigs.Trade.INPUT_ITEM_2.getStringValue(),
                AutoTradeConfigs.Trade.OUTPUT_ITEM.getStringValue());
        int targetItemCount = 0;
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
        return targetItemCount;
    }

    /** 每次打开交易界面后成交 TRADES_PER_SESSION 次；自动交易模式下不关闭界面 */
    private static void doPurchases(MinecraftClient mc, int syncId, List<Integer> indices, List<Integer> uses, List<Integer> refills) {
        if (!AutoTradeConfigs.isEnabled()) {
            return;
        }
        if (!validSession(mc, syncId)) {
            return;
        }
        if (indices.isEmpty()) {
            closeUnlessAutoTrade(mc, syncId);
            return;
        }
        int tradesPerSession = Math.max(1, AutoTradeConfigs.Trade.TRADES_PER_SESSION.getIntegerValue());
        performTrades(mc, syncId, indices, uses, refills, tradesPerSession);
        closeUnlessAutoTrade(mc, syncId);
    }

    /** 自动交易模式下村民界面保持打开（按周期重新打开，关闭功能时才关）；单次交易照旧买完即关 */
    private static void closeUnlessAutoTrade(MinecraftClient mc, int syncId) {
        if (AutoTradeConfigs.isAutoMode()) {
            return;
        }
        closeMerchantScreen(mc.getNetworkHandler(), syncId, mc);
    }

    /** 关闭自动交易时调用：关闭村民交易界面（真实界面或隐藏占位容器） */
    public static void closeTradeGui(MinecraftClient mc) {
        if (mc.player == null || !(mc.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
            return;
        }
        if (mc.getNetworkHandler() != null) {
            closeMerchantScreen(mc.getNetworkHandler(), handler.syncId, mc);
        }
        if (mc.currentScreen instanceof MerchantScreen) {
            mc.setScreen(null);
        }
    }

    /** 开始交易条件之二：村民交易界面已打开时，每交易间隔重新检查背包并在现有容器上开始一轮交易 */
    public static void purchaseCurrent(MinecraftClient mc) {
        if (!AutoTradeConfigs.isEnabled()) {
            return;
        }
        if (mc.player == null || !(mc.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
            return;
        }
        if (cachedOffers.isEmpty()) {
            return;
        }
        // 按交易间隔重新检查背包：用当前背包重算可买列表
        refreshBuyLists(mc.player, cachedOffers, new ArrayList<>());
        if (AutoTrade.tradeOfferIndex.isEmpty() || AutoTrade.tradeUsesLeft.isEmpty()) {
            return;
        }
        performTrades(mc, handler.syncId, new ArrayList<>(AutoTrade.tradeOfferIndex),
                new ArrayList<>(AutoTrade.tradeUsesLeft), new ArrayList<>(AutoTrade.tradeRefillCount),
                Math.max(1, AutoTradeConfigs.Trade.TRADES_PER_SESSION.getIntegerValue()));
    }

    /** 只执行成交（不关闭界面）；maxTrades 限制本次最多成交次数 */
    private static void performTrades(MinecraftClient mc, int syncId, List<Integer> indices, List<Integer> uses, List<Integer> refills, int maxTrades) {
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (mc.player == null || networkHandler == null || mc.interactionManager == null) {
            return;
        }
        // 每 gt 最多执行一轮成交：交易列表包触发与“界面已打开”触发可能同 gt到达，去重
        if (AutoTrade.tickId == lastPurchaseTickId) {
            return;
        }
        lastPurchaseTickId = AutoTrade.tickId;
        // 光标有残余时点输出槽无效，会卡死成交；先清理光标再开始
        clearCursor(mc, syncId);
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

    // 清理光标残余：优先合并进背包同类堆，其次放空位，都放不下就整堆丢出
    // 只考虑 36 个可映射到容器槽位的格子（盔甲/副手不可放入容器槽，映射会越界）
    private static void clearCursor(MinecraftClient mc, int syncId) {
        if (mc.player == null || mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return;
        }
        PlayerInventory inv = mc.player.getInventory();
        int storageSlots = Math.min(inv.size(), 36);
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = inv.getStack(i);
            ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
            if (cursor.isEmpty()) {
                return;
            }
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, cursor)
                    && stack.getCount() < stack.getMaxCount()) {
                mc.interactionManager.clickSlot(syncId, i < 27 ? 3 + i : 30 + (i - 27), 0, SlotActionType.PICKUP, mc.player);
            }
        }
        for (int i = 0; i < storageSlots; i++) {
            if (mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                return;
            }
            if (inv.getStack(i).isEmpty()) {
                mc.interactionManager.clickSlot(syncId, i < 27 ? 3 + i : 30 + (i - 27), 0, SlotActionType.PICKUP, mc.player);
            }
        }
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            // 背包放不下：点击界面外整堆丢出
            mc.interactionManager.clickSlot(syncId, -999, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    // 挑选一个用于放回光标物品的背包槽位（merchant 容器槽位映射：3..29 玩家主背包，30..38 快捷栏）。
    // 优先同物品可合并的槽，其次空槽；若背包全满则兜底第一个背包槽（可能交换滞留，见下轮清理）。
    // 只考虑 36 个可映射到容器槽位的格子，避免盔甲/副手越界。
    private static int findInventorySlotToPlace(MinecraftClient mc) {
        PlayerInventory inv = mc.player.getInventory();
        ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
        int storageSlots = Math.min(inv.size(), 36);

        for (int mainIndex = 0; mainIndex < storageSlots; mainIndex++) {
            ItemStack stack = inv.getStack(mainIndex);
            if (!stack.isEmpty() && !cursor.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, cursor)) {
                return mainIndex < 27 ? 3 + mainIndex : 30 + (mainIndex - 27);
            }
        }
        for (int mainIndex = 0; mainIndex < storageSlots; mainIndex++) {
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
        resetCachedOffers();
    }
}
