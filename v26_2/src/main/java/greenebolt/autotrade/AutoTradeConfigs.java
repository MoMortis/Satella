package greenebolt.autotrade;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AutoTradeConfigs implements IConfigHandler {
    public static final String MOD_ID = "satella";
    private static final AutoTradeConfigs INSTANCE = new AutoTradeConfigs();
    private static Path path;

    public static final class Trade {
        public static final ConfigBoolean ENABLED = new ConfigBoolean("启用自动交易", false, "启用自动交易");
        public static final ConfigOptionList MODE = new ConfigOptionList("交易模式", TradeMode.AUTO, "单次或自动轮询交易");
        public static final ConfigInteger TICK_INTERVAL = new ConfigInteger("交易间隔", 2, 1, 20, "轮询间隔（游戏刻）");
        public static final ConfigInteger TRADES_PER_SESSION = new ConfigInteger("每次交易次数", 20, 1, 100000, "每次打开交易的最大成交数量");
        public static final ConfigString INPUT_ITEM_1 = new ConfigString("输入物品1", "绿宝石", "物品 ID 或当前语言显示名");
        public static final ConfigString INPUT_ITEM_2 = new ConfigString("输入物品2", "", "可选的第二种输入物品");
        public static final ConfigString OUTPUT_ITEM = new ConfigString("输出物品", "铁锭", "物品 ID 或当前语言显示名");
        public static final ConfigBoolean DROP_OUTPUTS = new ConfigBoolean("交易后丢弃输出物品", false, "将交易结果丢弃到地面");
        public static final ConfigHotkey TOGGLE_KEY = new ConfigHotkey("自动交易开关键", "", KeybindSettings.DEFAULT, "开关自动交易");
        public static final ConfigHotkey MODE_KEY = new ConfigHotkey("切换交易模式键", "", KeybindSettings.DEFAULT, "切换交易模式");
        public static final ConfigBoolean RESIDUAL_CRAFTING = new ConfigBoolean("残差合成", false,
                "Ctrl+Alt+C 合成时保留每个背包材料堆叠中的一个物品");
        public static final ConfigBoolean AUTO_CRAFTING = new ConfigBoolean("全自动合成", false,
                "需要残差合成。自动使用附近工作台，并隐藏界面持续合成当前 Item Scroller 配方");
        public static final ConfigHotkey AUTO_CRAFTING_KEY = new ConfigHotkey("全自动合成开关键", "", KeybindSettings.DEFAULT,
                "开关全自动合成");
        public static final ConfigOptionList ENCHANTMENT_COLOR = new ConfigOptionList("附魔显示颜色", GlintPreset.WHITE,
                "点击切换预设附魔光效颜色");
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(ENABLED, MODE, TICK_INTERVAL, TRADES_PER_SESSION,
                INPUT_ITEM_1, INPUT_ITEM_2, OUTPUT_ITEM, DROP_OUTPUTS, TOGGLE_KEY, MODE_KEY, RESIDUAL_CRAFTING,
                AUTO_CRAFTING, AUTO_CRAFTING_KEY, ENCHANTMENT_COLOR);
    }

    public static void register() {
        path = FileUtils.getConfigDirectory().resolve(MOD_ID).resolve("Satella.json");
        ConfigManager.getInstance().registerConfigHandler(MOD_ID, INSTANCE);
        INSTANCE.load();
    }

    public static boolean isEnabled() { return Trade.ENABLED.getBooleanValue(); }
    public static boolean isAutoMode() { return Trade.MODE.getOptionListValue() == TradeMode.AUTO; }

    @Override public void load() {
        if (path != null && Files.exists(path)) {
            JsonElement json = JsonUtils.parseJsonFile(path);
            if (json != null && json.isJsonObject()) ConfigUtils.readConfigBase(json.getAsJsonObject(), "Trade", Trade.OPTIONS);
        }
    }

    @Override public void save() {
        if (path != null) {
            FileUtils.createDirectoriesIfMissing(path.getParent());
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Trade", Trade.OPTIONS);
            JsonUtils.writeJsonToFile(root, path);
        }
    }
}
