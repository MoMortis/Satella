package greenebolt.autotrade.stlocator;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.FileResourcePackProvider;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.SaveLoading;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.path.SymlinkFinder;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.level.storage.LevelStorage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯客户端世界生成栈：复刻 {@link SaveLoading} 的注册表加载流程，但不打开任何存档。
 * 原版数据包 + config/satella/datapacks 下的 zip 组合成复合资源包，解析全部动态注册表，
 * 并实例化 overworld 噪声生成器，使得任意种子下的群系/结构查询可以完全离线执行。
 */
public final class DatapackWorldgen implements AutoCloseable {
    public final DynamicRegistryManager.Immutable registryManager;
    public final NoiseChunkGenerator noiseGenerator;
    public final NoiseConfig noiseConfig;
    public final BiomeSource biomeSource;
    public final HeightLimitView heightView;
    public final StructurePlacementCalculator placementCalculator;
    public final StructureTemplateManager templateManager;
    public final List<String> loadedPacks;
    /** 容错加载时被跳过的元素（第三方数据包引用模组自定义注册表等） */
    public final Map<RegistryKey<?>, Exception> registryErrors;
    private final LifecycledResourceManager resourceManager;

    private DatapackWorldgen(
        DynamicRegistryManager.Immutable registryManager,
        NoiseChunkGenerator noiseGenerator,
        NoiseConfig noiseConfig,
        BiomeSource biomeSource,
        HeightLimitView heightView,
        StructurePlacementCalculator placementCalculator,
        StructureTemplateManager templateManager,
        LifecycledResourceManager resourceManager,
        List<String> loadedPacks,
        Map<RegistryKey<?>, Exception> registryErrors
    ) {
        this.registryManager = registryManager;
        this.noiseGenerator = noiseGenerator;
        this.noiseConfig = noiseConfig;
        this.biomeSource = biomeSource;
        this.heightView = heightView;
        this.placementCalculator = placementCalculator;
        this.templateManager = templateManager;
        this.resourceManager = resourceManager;
        this.loadedPacks = loadedPacks;
        this.registryErrors = registryErrors;
    }

    public static DatapackWorldgen load(Path packsDir, SymlinkFinder symlinkFinder, LevelStorage.Session templateSession,
                                        ResourceManager clientResources, DataFixer dataFixer, long seed) throws Exception {
        LifecycledResourceManager resourceManager = null;
        try {
            // 原版 + config/satella/datapacks + （fabric resource-loader 注入的）模组内置数据包
            ResourcePackManager packManager = new ResourcePackManager(
                new VanillaDataPackProvider(symlinkFinder),
                new FileResourcePackProvider(packsDir, ResourceType.SERVER_DATA, ResourcePackSource.WORLD, symlinkFinder));
            Pair<DataConfiguration, LifecycledResourceManager> loaded =
                new SaveLoading.DataPacks(packManager, DataConfiguration.SAFE_MODE, false, false).load();
            resourceManager = loaded.getSecond();

            CombinedDynamicRegistries<ServerDynamicRegistryType> combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
            List<Registry.PendingTagLoad<?>> pendingTags = TagGroupLoader.startReload(
                resourceManager, combined.get(ServerDynamicRegistryType.STATIC));
            DynamicRegistryManager.Immutable preceding = combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN);
            List<RegistryWrapper.Impl<?>> wrappers = TagGroupLoader.collectRegistries(preceding, pendingTags);

            // 容错加载：第三方数据包引用模组自定义注册表（如 lithostitched:fast_noise_config）时
            // 只跳过对应元素，不让整个注册表加载失败
            // fabric DynamicRegistries（Datapack Registries）注册的模组自定义注册表
            //（如 lithostitched:fast_noise_config）也要纳入加载，否则其密度函数无法解析
            List<RegistryLoader.Entry<?>> dynamicEntries = new ArrayList<>(RegistryLoader.DYNAMIC_REGISTRIES);
            dynamicEntries.addAll(net.fabricmc.fabric.api.event.registry.DynamicRegistries.getDynamicRegistries());

            Map<RegistryKey<?>, Exception> registryErrors = new HashMap<>();
            DynamicRegistryManager.Immutable dynamic = TolerantRegistryLoader.load(
                resourceManager, wrappers, dynamicEntries, registryErrors);
            for (Map.Entry<RegistryKey<?>, Exception> err : registryErrors.entrySet()) {
                StLocator.LOGGER.warn("跳过注册表元素 {}: {}", err.getKey().getValue(), (err.getValue().getCause() != null ? err.getValue().getCause().toString() : err.getValue().toString()));
            }
            List<RegistryWrapper.Impl<?>> allWrappers = new ArrayList<>(wrappers);
            dynamic.stream().forEach(allWrappers::add);
            DynamicRegistryManager.Immutable dimensions = TolerantRegistryLoader.load(
                resourceManager, allWrappers, RegistryLoader.DIMENSION_REGISTRIES, registryErrors);

            DimensionOptions dimension = pickNoiseDimension(dimensions);
            ChunkGenerator generator = dimension.chunkGenerator();
            if (!(generator instanceof NoiseChunkGenerator noiseGenerator)) {
                throw new IllegalStateException("数据包中没有基于噪声的维度");
            }

            ChunkGeneratorSettings settings = noiseGenerator.getSettings().value();
            NoiseConfig noiseConfig = NoiseConfig.create(settings, dimensions.getOrThrow(RegistryKeys.NOISE_PARAMETERS), seed);
            HeightLimitView heightView = HeightLimitView.create(
                settings.generationShapeConfig().minimumY(), settings.generationShapeConfig().height());
            StructurePlacementCalculator placementCalculator = StructurePlacementCalculator.create(
                noiseConfig, seed, noiseGenerator.getBiomeSource(), dimensions.getOrThrow(RegistryKeys.STRUCTURE_SET));
            StructureTemplateManager templateManager = new StructureTemplateManager(
                clientResources, templateSession, dataFixer, dimensions.getOrThrow(RegistryKeys.BLOCK));

            List<String> loadedPacks = new ArrayList<>();
            for (var profile : packManager.getEnabledProfiles()) {
                loadedPacks.add(profile.getId());
            }

            return new DatapackWorldgen(dimensions, noiseGenerator, noiseConfig, noiseGenerator.getBiomeSource(),
                heightView, placementCalculator, templateManager, resourceManager, loadedPacks, registryErrors);
        } catch (Exception e) {
            // 失败时释放已打开的资源包（zip 句柄）；模板会话由外部持久持有，不受影响
            if (resourceManager != null) {
                resourceManager.close();
            }
            throw e;
        }
    }

    private static DimensionOptions pickNoiseDimension(DynamicRegistryManager.Immutable dimensions) {
        Registry<DimensionOptions> registry = dimensions.getOrThrow(RegistryKeys.DIMENSION);
        DimensionOptions overworld = registry.get(RegistryKey.of(RegistryKeys.DIMENSION, Identifier.ofVanilla("overworld")));
        if (overworld != null && overworld.chunkGenerator() instanceof NoiseChunkGenerator) {
            return overworld;
        }
        return registry.streamEntries()
            .map(RegistryEntry::value)
            .filter(entry -> entry.chunkGenerator() instanceof NoiseChunkGenerator)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("数据包中没有基于噪声的维度"));
    }

    @Override
    public void close() {
        resourceManager.close();
    }
}
