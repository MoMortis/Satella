package greenebolt.autotrade;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ItemNameUtils {
    private static final Map<String, Item> NAME_MAP = new HashMap<>();
    private static Language loadedLanguage = null;

    public static Item parseItem(String str) {
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // 1) 优先按物品 id 解析：支持 "minecraft:emerald"、带其它命名空间的 id、省略 "minecraft:" 的 "emerald"
        //    id 部分统一转小写，避免 "Emerald" 这类大小写导致解析失败
        String idStr = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
        Identifier id = Identifier.tryParse(idStr.toLowerCase(Locale.ROOT));
        if (id != null && Registries.ITEM.containsId(id)) {
            return Registries.ITEM.get(id);
        }

        // 2) 按物品显示名解析（当前游戏语言），名字不区分大小写
        return nameMap().get(trimmed.toLowerCase(Locale.ROOT));
    }

    public static boolean matches(String configEntry, Item item) {
        Item parsed = parseItem(configEntry);
        if (parsed != null) {
            return parsed == item;
        }
        return Registries.ITEM.getId(item).getPath().equals(configEntry.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Item> nameMap() {
        // 语言包加载/切换语言时 Language.getInstance() 会返回新的实例，通过实例引用对比自动重建，
        // 避免切语言或 F3+T 重载资源后显示名失配
        Language current = Language.getInstance();
        if (loadedLanguage != current) {
            NAME_MAP.clear();
            loadedLanguage = current;
            buildNameMap();
        }
        return NAME_MAP;
    }

    private static void buildNameMap() {
        Language language = Language.getInstance();
        for (Item item : Registries.ITEM) {
            String name = language.get(item.getTranslationKey());
            if (name != null && !name.isEmpty() && !name.equals(item.getTranslationKey())) {
                NAME_MAP.put(name, item);
                NAME_MAP.put(name.toLowerCase(Locale.ROOT), item);
            }
        }
    }

    // 在进入世界后预热物品名映射（语言包已加载），避免 mod 初始化时语言未就绪导致名字匹配不到
    public static void warmup() {
        nameMap();
    }
}
