package greenebolt.autotrade.stlocator;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.MutableRegistry;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntryInfo;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.StrictJsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 逐元素容错的动态注册表加载：复刻 {@link RegistryLoader} 的资源加载循环，
 * 但解析失败的数据包元素只记入错误表并跳过，不再导致整个注册表/整个加载失败。
 * 这允许带第三方自定义注册表（如 lithostitched:fast_noise_config）的数据包
 * 在纯客户端离线栈中尽可能完整地加载。
 */
final class TolerantRegistryLoader {

    private TolerantRegistryLoader() {}

    static DynamicRegistryManager.Immutable load(
        ResourceManager resourceManager,
        List<RegistryWrapper.Impl<?>> baseWrappers,
        List<RegistryLoader.Entry<?>> entries,
        Map<RegistryKey<?>, Exception> errors
    ) {
        List<SimpleRegistry<?>> registries = new ArrayList<>(entries.size());
        Map<RegistryKey<? extends Registry<?>>, RegistryOps.RegistryInfo<?>> infos = new HashMap<>();
        for (RegistryWrapper.Impl<?> wrapper : baseWrappers) {
            infos.put(wrapper.getKey(), RegistryOps.RegistryInfo.fromWrapper(wrapper));
        }
        // 与原版一致：先注册所有 in-flight 注册表再解析，保证注册表之间的交叉引用可解析
        for (RegistryLoader.Entry<?> entry : entries) {
            SimpleRegistry<?> registry = new SimpleRegistry<>(entry.key(), Lifecycle.stable());
            registries.add(registry);
            infos.put(registry.getKey(), infoOf(registry));
        }
        RegistryOps.RegistryInfoGetter getter = new RegistryOps.RegistryInfoGetter() {
            @Override
            public <T> Optional<RegistryOps.RegistryInfo<T>> getRegistryInfo(RegistryKey<? extends Registry<? extends T>> registryRef) {
                return Optional.ofNullable((RegistryOps.RegistryInfo<T>) infos.get(registryRef));
            }
        };

        for (int i = 0; i < entries.size(); i++) {
            loadEntry(resourceManager, getter, entries.get(i), registries.get(i), errors);
        }
        // freeze 失败（如存在无法解析的悬空引用）时跳过该注册表，避免影响整体加载
        List<Registry<?>> frozen = new ArrayList<>(registries.size());
        for (SimpleRegistry<?> registry : registries) {
            try {
                registry.freeze();
                frozen.add(registry);
            } catch (Exception e) {
                errors.put(registry.getKey(), e);
            }
        }
        return new DynamicRegistryManager.ImmutableImpl(frozen).toImmutable();
    }

    private static <T> RegistryOps.RegistryInfo<T> infoOf(MutableRegistry<T> registry) {
        // 与原版一致使用 createMutableRegistryLookup：允许引用尚未解析的元素（惰性占位 Holder）
        return new RegistryOps.RegistryInfo<>(registry, registry.createMutableRegistryLookup(), Lifecycle.stable());
    }

    private static void loadEntry(
        ResourceManager resourceManager,
        RegistryOps.RegistryInfoGetter getter,
        RegistryLoader.Entry<?> entry,
        MutableRegistry<?> registry,
        Map<RegistryKey<?>, Exception> errors
    ) {
        loadEntryRaw(resourceManager, getter, (RegistryLoader.Entry<Object>) entry, (MutableRegistry<Object>) registry, errors);
    }

    private static <T> void loadEntryRaw(
        ResourceManager resourceManager,
        RegistryOps.RegistryInfoGetter getter,
        RegistryLoader.Entry<T> entry,
        MutableRegistry<T> registry,
        Map<RegistryKey<?>, Exception> errors
    ) {
        ResourceFinder finder = ResourceFinder.json(entry.key());
        RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, getter);
        for (Map.Entry<Identifier, Resource> e : finder.findResources(resourceManager).entrySet()) {
            Identifier path = e.getKey();
            Resource resource = e.getValue();
            RegistryKey<T> key = RegistryKey.of(entry.key(), finder.toResourceId(path));
            try (Reader reader = resource.getReader()) {
                JsonElement json = StrictJsonParser.parse(reader);
                T value = entry.elementCodec().parse(ops, json).getOrThrow();
                registry.add(key, value, RegistryEntryInfo.DEFAULT);
            } catch (Exception ex) {
                errors.put(key, new IllegalStateException(
                    "Failed to parse " + path + " from pack " + resource.getPackId(), ex));
            }
        }
        TagGroupLoader.loadInitial(resourceManager, registry);
    }
}
