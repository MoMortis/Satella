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

public class AutoTradeConfigs implements IConfigHandler {
    public static final String MOD_ID = "satella";
    private static final String CONFIG_FILE_NAME = "Satella.json";
    private static final String CATEGORY = "Trade";
    private static final AutoTradeConfigs INSTANCE = new AutoTradeConfigs();

    private static Path configPath;

    public static class Trade {
        public static final ConfigBoolean ENABLED = new ConfigBoolean(
                "启用自动交易", false,
                "总开关。开启后点击村民交易界面会自动购买匹配的交易，自动交易模式下首次点击的村民会加入交易列表");

        public static final ConfigOptionList MODE = new ConfigOptionList(
                "交易模式", TradeMode.AUTO_TRADE,
                "单次交易：手动点击村民，买空其所有匹配的交易后结束\n自动交易：开启后右键村民建立持续交易容器（发光高亮），关闭自动交易时关闭容器");

        public static final ConfigInteger TICK_INTERVAL = new ConfigInteger(
                "交易间隔", 2, 1, 20,
                "自动交易模式下，每隔多少游戏刻对列表中的下一个村民执行一次交易");

        public static final ConfigInteger TRADES_PER_SESSION = new ConfigInteger(
                "每次交易次数", 20, 1, 100000,
                "每个交易间隔打开村民交易界面后，本次最多点击交易按钮多少次；每次点击成交 1 个输出物品。\n" +
                "例：交易间隔 2、交易次数 20，即每 2 游戏刻成交 20 个输出物品；买不完的会留到下一个交易间隔继续");

        public static final ConfigString INPUT_ITEM_1 = new ConfigString(
                "输入物品1", "绿宝石",
                "交易输入物品（村民要的东西），支持物品显示名、物品 id（如 minecraft:emerald）或省略 minecraft: 的 id（如 emerald）");

        public static final ConfigString INPUT_ITEM_2 = new ConfigString(
                "输入物品2", "",
                "第二个交易输入物品，没有可留空。如：纸 或 paper\n注意：村民的第二个输入必须是这里配置的物品");

        public static final ConfigString OUTPUT_ITEM = new ConfigString(
                "输出物品", "铁锭",
                "交易输出物品（村民给你的东西），支持物品显示名、物品 id（如 minecraft:iron_ingot）或省略 minecraft: 的 id（如 iron_ingot）");

        public static final ConfigBoolean DROP_OUTPUTS = new ConfigBoolean(
                "交易后丢弃输出物品", false,
                "开启后交易得到的输出物品不会进入背包，而是直接丢在地上\n关闭则输出物品自动放进背包");

        public static final ConfigHotkey TOGGLE_KEY = new ConfigHotkey(
                "自动交易开关键", "", KeybindSettings.DEFAULT,
                "按下开启或关闭自动交易（默认未绑定）");

        public static final ConfigHotkey MODE_KEY = new ConfigHotkey(
                "切换交易模式键", "", KeybindSettings.DEFAULT,
                "在 单次交易 与 自动交易 之间切换（默认未绑定）");

        public static final ConfigBoolean BETTER_CROSSBOW = new ConfigBoolean(
                "更NB的弩", false,
                "手持弩长按右键时，持续使用并按周期重复执行右键");

        public static final ConfigInteger BETTER_CROSSBOW_INTERVAL = new ConfigInteger(
                "更NB的弩周期", 20, 1, 100000,
                "更NB的弩每隔多少游戏刻执行一次周期性右键");

        public static final ConfigBoolean SERVER_SHULKER_COMPAT = new ConfigBoolean(
                "服务器快捷潜影盒兼容", false,
                "让 Item Scroller、Inventory Profiles Next 和 Tweakeroo 忽略指定潜影盒组件，配置文件位于 config/satella/ignored-components.txt");

        public static final ConfigInteger CRAFT_RESIDUE = new ConfigInteger(
                "合成残余", 0, 0, 16,
                "残差合成补料时，若背包中某材料堆数量 P 满足 0.5*P < 该值，则跳过该堆\n单位：个");

        public static final ConfigBoolean RESIDUAL_CRAFTING = new ConfigBoolean(
                "残差合成", false,
                "修改 Item Scroller 的快速合成：搬运材料时，背包中每个同材料堆叠都会保留 1 个\n开启后会强制 Item Scroller 使用 fallback 合成逻辑");

        public static final ConfigBoolean AUTO_CRAFTING = new ConfigBoolean(
                "全自动合成", false,
                "需要先开启残差合成。开启后自动寻找附近工作台，隐藏工作台界面并持续按当前 Item Scroller 配方合成");

        public static final ConfigHotkey AUTO_CRAFTING_KEY = new ConfigHotkey(
                "全自动合成开关键", "", KeybindSettings.DEFAULT,
                "按下开启或关闭全自动合成（默认未绑定）");

        public static final ConfigOptionList ENCHANTMENT_COLOR = new ConfigOptionList(
                "附魔显示颜色", GlintPreset.WHITE, "点击切换预设附魔光效颜色");

        public static final ConfigStringList DROP_BLOCK_ITEMS = new ConfigStringList(
                "拦截目标物品丢弃", ImmutableList.of(),
                "列表内的物品无法被丢弃：手持 Q / Ctrl+Q、容器内按 Q / Ctrl+Q、光标移出界面丢弃等全部拦截\n"
                        + "支持物品显示名、物品 id（如 minecraft:diamond）或省略 minecraft: 的 id（如 diamond）");

        public static final ConfigStringList ST_RULES = new ConfigStringList(
                "多环定位规则", ImmutableList.of(),
                "用 /st rules 指令打开编辑器配置。每条格式：最小距离-最大距离:种子[:数据包1|数据包2]\n"
                        + "距离 = 检索中心（玩家位置）到世界原点 (0,0) 的切比雪夫距离（方块）\n"
                        + "数据包为 config/satella/datapacks 下的 zip 文件名（可省略 .zip），用 | 分隔；省略数据包部分时使用全部\n"
                        + "例：0-4096:123456 与 4097-999999:654321:tectonic-datapack-3.0.18|Dungeons and Taverns v5.1.0\n"
                        + "未命中任何环时，使用 /st seed 设置的全局种子且不加载任何数据包 zip");

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                ENABLED, MODE, TICK_INTERVAL, TRADES_PER_SESSION, INPUT_ITEM_1, INPUT_ITEM_2, OUTPUT_ITEM,
                DROP_OUTPUTS, TOGGLE_KEY, MODE_KEY, BETTER_CROSSBOW, BETTER_CROSSBOW_INTERVAL, SERVER_SHULKER_COMPAT,
                CRAFT_RESIDUE, RESIDUAL_CRAFTING, AUTO_CRAFTING, AUTO_CRAFTING_KEY, ENCHANTMENT_COLOR, DROP_BLOCK_ITEMS, ST_RULES);
    }

    public static void register() {
        configPath = FileUtils.getConfigDirectory().resolve(MOD_ID).resolve(CONFIG_FILE_NAME);
        ConfigManager.getInstance().registerConfigHandler(MOD_ID, INSTANCE);
        INSTANCE.load();
    }

    /** 供规则编辑器等外部界面立即落盘配置 */
    public static void saveNow() {
        INSTANCE.save();
    }

    public static boolean isEnabled() {
        return Trade.ENABLED.getBooleanValue();
    }

    public static boolean isAutoMode() {
        return Trade.MODE.getOptionListValue() == TradeMode.AUTO_TRADE;
    }

    @Override
    public void load() {
        if (configPath != null && Files.exists(configPath)) {
            JsonElement element = JsonUtils.parseJsonFile(configPath);
            if (element != null && element.isJsonObject()) {
                ConfigUtils.readConfigBase(element.getAsJsonObject(), CATEGORY, Trade.OPTIONS);
            }
        }
    }

    @Override
    public void save() {
        if (configPath != null) {
            FileUtils.createDirectoriesIfMissing(configPath.getParent());
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, CATEGORY, Trade.OPTIONS);
            JsonUtils.writeJsonToFile(root, configPath);
        }
    }
}
