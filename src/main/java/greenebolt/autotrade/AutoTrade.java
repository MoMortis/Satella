package greenebolt.autotrade;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.data.ModInfo;
import greenebolt.autotrade.gui.AutoTradeConfigGui;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AutoTrade implements ModInitializer, IKeybindProvider, IHotkeyCallback {
    public static final String MOD_ID = "satella";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static List<Integer> tradeOfferIndex = new ArrayList<>();
    public static List<Integer> tradeUsesLeft = new ArrayList<>();
    // 每个交易每次 SelectMerchantTrade（服务端 autofill 填满输入槽）后能支撑的成交次数
    public static List<Integer> tradeRefillCount = new ArrayList<>();

    private static final Set<Integer> trackedVillagers = new LinkedHashSet<>();
    private static UUID trackedVillagerUuid;
    private static final Map<Integer, Boolean> lastTradeState = new HashMap<>();
    private static Entity lastInteractedEntity;
    private static boolean autoOpening = false;
    private int tickCounter;
    private int betterCrossbowCounter;
    private boolean betterCrossbowActive;
    private static boolean physicalUseKeyDown;

    @Override
    public void onInitialize() {
        AutoTradeConfigs.register();
        ShulkerCompatConfig.load();

        greenebolt.autotrade.stlocator.StLocator.init(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("satella"));
        greenebolt.autotrade.stlocator.StCommands.register();

        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(MOD_ID, "Satella", AutoTradeConfigGui::new));

        AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.MODE_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind().setCallback(this);
        InputEventHandler.getKeybindManager().registerKeybindProvider(this);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 进入世界后再预热物品名映射，此时语言包已加载，中文等显示名才能匹配到
            if (client.world != null) {
                ItemNameUtils.warmup();
            }
            tickBetterCrossbow(client);
            AutoCraftController.tick(client);
            AutoStonecutController.tick(client);
            tickCounter++;
            if (tickCounter >= AutoTradeConfigs.Trade.TICK_INTERVAL.getIntegerValue()) {
                tickCounter = 0;
                if (client.player == null || client.world == null || !AutoTradeConfigs.isEnabled()
                        || !AutoTradeConfigs.isAutoMode()) {
                    return;
                }
                pollNextVillager(client);
            }
        });
    }

    private void tickBetterCrossbow(MinecraftClient client) {
        if (client.player == null || client.world == null
                || !AutoTradeConfigs.Trade.BETTER_CROSSBOW.getBooleanValue()
                || !physicalUseKeyDown || !isHoldingCrossbow(client)) {
            resetBetterCrossbow(client);
            return;
        }

        // 背包/其他容器界面打开、或 litematica-printer 快捷潜影盒-自动补货进行中时，
        // 立即暂停连射：松开模拟的使用键并清零计数，恢复后从激活步骤重新开始
        // （先按住使用键，再进入周期射击），避免补货期间射击导致补货失败
        if (client.currentScreen != null || PrinterRestockPause.isRestockInProgress()) {
            if (betterCrossbowActive) {
                setUseKey(client, false);
            }
            betterCrossbowActive = false;
            betterCrossbowCounter = 0;
            return;
        }

        if (!betterCrossbowActive) {
            betterCrossbowActive = true;
            betterCrossbowCounter = 0;
            setUseKey(client, true);
            return;
        }

        // Keep the vanilla use key pressed first, then perform the periodic click.
        setUseKey(client, true);
        if (++betterCrossbowCounter >= AutoTradeConfigs.Trade.BETTER_CROSSBOW_INTERVAL.getIntegerValue()) {
            betterCrossbowCounter = 0;
            ((AutoTradeMinecraftClient) client).autoTrade$doItemUse();
        }
    }

    private static boolean isHoldingCrossbow(MinecraftClient client) {
        return isCrossbow(client.player.getMainHandStack()) || isCrossbow(client.player.getOffHandStack());
    }

    private static boolean isCrossbow(ItemStack stack) {
        return stack.getItem() instanceof CrossbowItem;
    }

    private void resetBetterCrossbow(MinecraftClient client) {
        if (betterCrossbowActive) {
            setUseKey(client, false);
        }
        betterCrossbowActive = false;
        betterCrossbowCounter = 0;
    }

    private static void setUseKey(MinecraftClient client, boolean pressed) {
        KeyBinding.setKeyPressed(client.options.useKey.getDefaultKey(), pressed);
    }

    public static void updatePhysicalUseKeyState(boolean pressed) {
        physicalUseKeyDown = pressed;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        manager.addKeybindToMap(AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.MODE_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind());
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(MOD_ID, "自动交易", List.of(
                AutoTradeConfigs.Trade.TOGGLE_KEY,
                AutoTradeConfigs.Trade.MODE_KEY,
                AutoTradeConfigs.Trade.AUTOMATION_KEY,
                AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY));
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        if (key == AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind()) {
            toggleEnabled();
        } else if (key == AutoTradeConfigs.Trade.MODE_KEY.getKeybind()) {
            cycleMode();
        } else if (key == AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind()) {
            toggleAutomation();
        } else if (key == AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind()) {
            cycleAutomationMode();
        }
        return true;
    }

    public void toggleEnabled() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }

        boolean enabled = !AutoTradeConfigs.isEnabled();
        AutoTradeConfigs.Trade.ENABLED.setBooleanValue(enabled);
        ConfigManager.getInstance().onConfigsChanged(MOD_ID);

        if (enabled) {
            InfoUtils.sendVanillaMessage(Text.literal("自动交易已开启 (").formatted(Formatting.GREEN)
                    .append(Text.literal("模式: " + AutoTradeConfigs.Trade.MODE.getOptionListValue().getDisplayName())));
        } else {
            trackedVillagers.clear();
            trackedVillagerUuid = null;
            lastTradeState.clear();
            InfoUtils.sendVanillaMessage(Text.literal("自动交易已关闭").formatted(Formatting.RED));
        }

        tradeOfferIndex.clear();
        tradeUsesLeft.clear();
        tradeRefillCount.clear();
    }

    private void toggleAutomation() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }

        boolean enabled = !AutoTradeConfigs.Trade.AUTOMATION.getBooleanValue();
        AutoTradeConfigs.Trade.AUTOMATION.setBooleanValue(enabled);
        if (!enabled) {
            AutoCraftController.close(mc);
            AutoStonecutController.close(mc);
        }
        InfoUtils.sendVanillaMessage(Text.literal(enabled ? "自动化已开启" : "自动化已关闭")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED));
    }

    private void cycleAutomationMode() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }

        IConfigOptionListEntry newMode = AutoTradeConfigs.Trade.AUTOMATION_MODE.getOptionListValue().cycle(true);
        AutoTradeConfigs.Trade.AUTOMATION_MODE.setOptionListValue(newMode);
        AutoCraftController.close(mc);
        AutoStonecutController.close(mc);

        InfoUtils.sendVanillaMessage(Text.literal("自动化模式: ").formatted(Formatting.YELLOW)
                .append(Text.literal(newMode.getDisplayName()).formatted(Formatting.GOLD)));
    }

    public void cycleMode() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }

        IConfigOptionListEntry newMode = AutoTradeConfigs.Trade.MODE.getOptionListValue().cycle(true);
        AutoTradeConfigs.Trade.MODE.setOptionListValue(newMode);
        ConfigManager.getInstance().onConfigsChanged(MOD_ID);

        InfoUtils.sendVanillaMessage(Text.literal("交易模式已切换: ").formatted(Formatting.YELLOW)
                .append(Text.literal(newMode.getDisplayName()).formatted(Formatting.GOLD)));

        tradeOfferIndex.clear();
        tradeUsesLeft.clear();
        tradeRefillCount.clear();
    }

    private void pollNextVillager(MinecraftClient client) {
        if (trackedVillagers.isEmpty()) {
            return;
        }

        List<Integer> ids = new ArrayList<>(trackedVillagers);
        ids.removeIf(id -> {
            Entity entity = client.world.getEntityById(id);
            if (!(entity instanceof VillagerEntity)) {
                return true;
            }
            return entity.isRemoved() || client.player.squaredDistanceTo(entity) > 8.0 * 8.0;
        });
        trackedVillagers.retainAll(ids);
        lastTradeState.keySet().removeIf(id -> !trackedVillagers.contains(id));

        if (ids.isEmpty()) {
            return;
        }

        Entity target = client.world.getEntityById(ids.get(0));
        if (target instanceof VillagerEntity villager && client.interactionManager != null) {
            autoOpening = true;
            client.interactionManager.interactEntity(client.player, villager, Hand.MAIN_HAND);
            autoOpening = false;
        }
    }

    public static void onInteractEntity(Entity entity) {
        if (autoOpening || !(entity instanceof VillagerEntity)) {
            return;
        }
        lastInteractedEntity = entity;

        if (!AutoTradeConfigs.isEnabled() || !AutoTradeConfigs.isAutoMode()) {
            return;
        }
        if (trackedVillagers.contains(entity.getId())) {
            return;
        }
        trackedVillagers.clear();
        lastTradeState.clear();
        trackedVillagers.add(entity.getId());
        trackedVillagerUuid = entity.getUuid();
        InfoUtils.sendVanillaMessage(Text.literal("已标记为目标村民，将自动轮询交易（发光标记）").formatted(Formatting.GREEN));
    }

    public static boolean isHighlighted(Entity entity) {
        return trackedVillagerUuid != null && trackedVillagerUuid.equals(entity.getUuid());
    }

    public static int getCurrentVillagerId() {
        if (lastInteractedEntity instanceof VillagerEntity) {
            return lastInteractedEntity.getId();
        }
        return -1;
    }

    public static boolean isTracked(int villagerId) {
        return trackedVillagers.contains(villagerId);
    }

    public static void onVillagerBuying(int villagerId) {
        if (villagerId < 0) {
            return;
        }
        lastTradeState.put(villagerId, true);
    }

    public static void onVillagerBoughtOut(int villagerId, String reason) {
        if (villagerId < 0 || !trackedVillagers.contains(villagerId)) {
            InfoUtils.sendVanillaMessage(Text.literal("该村民的交易已全部买空")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal(reason.isEmpty() ? "" : "（" + reason + "）").formatted(Formatting.RED)));
            return;
        }
        if (!Boolean.FALSE.equals(lastTradeState.get(villagerId))) {
            lastTradeState.put(villagerId, false);
            InfoUtils.sendVanillaMessage(Text.literal("村民已买空，将继续按间隔轮询检查（补充背包后会自动继续）")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal(reason.isEmpty() ? "" : "（" + reason + "）").formatted(Formatting.RED)));
        }
    }

    public static void onVillagerNoTrades(int villagerId) {
        if (villagerId < 0 || !trackedVillagers.contains(villagerId)) {
            InfoUtils.sendVanillaMessage(Text.literal("该村民没有匹配的交易").formatted(Formatting.RED));
            return;
        }
        if (!Boolean.FALSE.equals(lastTradeState.get(villagerId))) {
            lastTradeState.put(villagerId, false);
            InfoUtils.sendVanillaMessage(Text.literal("该村民没有匹配的交易，将继续按间隔轮询检查").formatted(Formatting.YELLOW));
        }
    }
}
