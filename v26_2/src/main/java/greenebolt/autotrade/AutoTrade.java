package greenebolt.autotrade;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ModInitializer;
import greenebolt.autotrade.gui.AutoTradeConfigGui;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AutoTrade implements ModInitializer, IKeybindProvider, IHotkeyCallback {
    public static final String MOD_ID = "satella";
    // 用村民的 UUID（跨重进不变）做交易目标标识；实体数字 ID 每次登录会重新分配，不能持久跟踪
    private static final Set<UUID> TRACKED_VILLAGERS = new LinkedHashSet<>();
    private static boolean autoOpening;

    @Override public void onInitialize() {
        AutoTradeConfigs.register();
        greenebolt.autotrade.stlocator.StLocator.init(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("satella"));
        greenebolt.autotrade.stlocator.StCommands.register();
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(MOD_ID, "Satella", AutoTradeConfigGui::new));
        AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.MODE_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind().setCallback(this);
        AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind().setCallback(this);
        InputEventHandler.getKeybindManager().registerKeybindProvider(this);
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level != null) ItemNameUtils.warmup();
        AutoCraftController.tick(minecraft);
        AutoStonecutController.tick(minecraft);
        if (++tickCounter < AutoTradeConfigs.Trade.TICK_INTERVAL.getIntegerValue()) return;
        tickCounter = 0;
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null || !AutoTradeConfigs.isEnabled() || !AutoTradeConfigs.isAutoMode()) return;
        TRACKED_VILLAGERS.removeIf(uuid -> {
            Entity entity = findEntity(minecraft, uuid);
            return !(entity instanceof Villager) || entity.isRemoved() || minecraft.player.distanceToSqr(entity) > 64.0;
        });
        if (TRACKED_VILLAGERS.isEmpty()) return;
        Entity entity = findEntity(minecraft, TRACKED_VILLAGERS.iterator().next());
        if (entity instanceof Villager villager) {
            autoOpening = true;
            minecraft.gameMode.interact(minecraft.player, villager, new EntityHitResult(villager), InteractionHand.MAIN_HAND);
            autoOpening = false;
        }
    }

    /** 26.2 的 ClientLevel 没有公开的按 UUID 查找，只能遍历已加载实体比对 */
    private static Entity findEntity(Minecraft minecraft, UUID uuid) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID())) {
                return entity;
            }
        }
        return null;
    }

    private static int tickCounter;

    @Override public void addKeysToMap(IKeybindManager manager) {
        manager.addKeybindToMap(AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.MODE_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind());
        manager.addKeybindToMap(AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind());
    }

    @Override public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(MOD_ID, "自动交易", List.of(AutoTradeConfigs.Trade.TOGGLE_KEY,
                AutoTradeConfigs.Trade.MODE_KEY, AutoTradeConfigs.Trade.AUTOMATION_KEY,
                AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY));
    }

    @Override public boolean onKeyAction(KeyAction action, IKeybind key) {
        if (key == AutoTradeConfigs.Trade.TOGGLE_KEY.getKeybind()) toggle();
        else if (key == AutoTradeConfigs.Trade.MODE_KEY.getKeybind()) cycleMode();
        else if (key == AutoTradeConfigs.Trade.AUTOMATION_KEY.getKeybind()) toggleAutomation();
        else if (key == AutoTradeConfigs.Trade.AUTOMATION_MODE_KEY.getKeybind()) cycleAutomationMode();
        return true;
    }

    private static void toggle() {
        boolean enabled = !AutoTradeConfigs.isEnabled();
        AutoTradeConfigs.Trade.ENABLED.setBooleanValue(enabled);
        ConfigManager.getInstance().onConfigsChanged(MOD_ID);
        if (!enabled) {
            TRACKED_VILLAGERS.clear();
        }
        InfoUtils.sendVanillaMessage(Component.literal(enabled ? "自动交易已开启" : "自动交易已关闭").withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static void cycleMode() {
        var mode = AutoTradeConfigs.Trade.MODE.getOptionListValue().cycle(true);
        AutoTradeConfigs.Trade.MODE.setOptionListValue(mode);
        ConfigManager.getInstance().onConfigsChanged(MOD_ID);
        InfoUtils.sendVanillaMessage(Component.literal("交易模式已切换: " + mode.getDisplayName()).withStyle(ChatFormatting.YELLOW));
    }

    private static void toggleAutomation() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        boolean enabled = !AutoTradeConfigs.Trade.AUTOMATION.getBooleanValue();
        AutoTradeConfigs.Trade.AUTOMATION.setBooleanValue(enabled);
        if (!enabled) {
            AutoCraftController.close(minecraft);
            AutoStonecutController.close(minecraft);
        }
        InfoUtils.sendVanillaMessage(Component.literal(enabled ? "自动化已开启" : "自动化已关闭")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static void cycleAutomationMode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        var newMode = AutoTradeConfigs.Trade.AUTOMATION_MODE.getOptionListValue().cycle(true);
        AutoTradeConfigs.Trade.AUTOMATION_MODE.setOptionListValue(newMode);
        AutoCraftController.close(minecraft);
        AutoStonecutController.close(minecraft);
        InfoUtils.sendVanillaMessage(Component.literal("自动化模式: " + newMode.getDisplayName())
                .withStyle(ChatFormatting.GOLD));
    }

    public static void onInteractEntity(Entity entity) {
        if (autoOpening || !(entity instanceof Villager)) return;
        if (AutoTradeConfigs.isEnabled() && AutoTradeConfigs.isAutoMode()) {
            TRACKED_VILLAGERS.clear();
            TRACKED_VILLAGERS.add(entity.getUUID());
            InfoUtils.sendVanillaMessage(Component.literal("已标记为目标村民").withStyle(ChatFormatting.GREEN));
        }
    }

    public static boolean isHighlighted(Entity entity) {
        return TRACKED_VILLAGERS.contains(entity.getUUID());
    }
}
