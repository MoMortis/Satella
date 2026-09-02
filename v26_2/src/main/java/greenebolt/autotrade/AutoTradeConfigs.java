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
import fi.dy.masa.malilib.config.options.ConfigStringList;
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
        public static final ConfigInteger CRAFT_RESIDUE = new ConfigInteger("合成残余", 0, 0, 32,
                "残差合成补料时，若背包中某材料堆数量 P 满足 0.5*P < 该值，则跳过该堆（单位：个）");
        public static final ConfigInteger AUTO_CRAFTING_INTERVAL = new ConfigInteger("全自动合成周期", 3, 1, 100,
                "全自动合成每隔多少游戏刻执行一次合成，与 Item Scroller 配置无关");
        public static final ConfigBoolean AUTO_CRAFTING = new ConfigBoolean("全自动合成", false,
                "自动寻找附近工作台，隐藏界面并持续按当前 Item Scroller 配方合成");
        public static final ConfigHotkey AUTO_CRAFTING_KEY = new ConfigHotkey("全自动合成开关键", "", KeybindSettings.DEFAULT,
                "开关全自动合成");
        public static final ConfigOptionList ENCHANTMENT_COLOR = new ConfigOptionList("附魔显示颜色", GlintPreset.WHITE,
                "点击切换预设附魔光效颜色");

        public static final ConfigStringList DROP_BLOCK_ITEMS = new ConfigStringList("拦截目标物品丢弃", ImmutableList.of(),
                "列表内的物品仅可通过“拿起物品后光标移出界面点击丢弃”这一种方式丢弃：手持 Q / Ctrl+Q、背包/容器内按 Q / Ctrl+Q 等全部拦截\n"
                        + "支持物品显示名、物品 id（如 minecraft:diamond）或省略 minecraft: 的 id（如 diamond）");

        public static final ConfigStringList ST_RULES = new ConfigStringList(
                "多环定位规则", ImmutableList.of(),
                "用 /st rules 指令打开编辑器配置。每条格式：最小距离-最大距离:种子[:数据包1|数据包2]\n"
                        + "距离 = 检索中心（玩家位置）到世界原点 (0,0) 的切比雪夫距离（方块）\n"
                        + "数据包为 config/satella/datapacks 下的 zip 文件名（可省略 .zip），用 | 分隔；省略数据包部分时使用全部\n"
                        + "例：0-4096:123456 与 4097-999999:654321:tectonic-datapack-3.0.18|Dungeons and Taverns v5.1.0\n"
                        + "未命中任何环时，使用 /st seed 设置的全局种子且不加载任何数据包 zip");
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(ENABLED, MODE, TICK_INTERVAL, TRADES_PER_SESSION,
                INPUT_ITEM_1, INPUT_ITEM_2, OUTPUT_ITEM, DROP_OUTPUTS, TOGGLE_KEY, MODE_KEY, CRAFT_RESIDUE,
                AUTO_CRAFTING_INTERVAL, AUTO_CRAFTING, AUTO_CRAFTING_KEY, ENCHANTMENT_COLOR, DROP_BLOCK_ITEMS, ST_RULES);
    }

    public static void register() {
        path = FileUtils.getConfigDirectory().resolve(MOD_ID).resolve("Satella.json");
        ConfigManager.getInstance().registerConfigHandler(MOD_ID, INSTANCE);
        INSTANCE.load();
    }

    /** 供规则编辑器等外部界面立即落盘配置 */
    public static void saveNow() {
        INSTANCE.save();
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
