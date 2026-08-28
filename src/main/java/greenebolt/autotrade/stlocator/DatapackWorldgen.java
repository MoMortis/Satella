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
import java.util.List;
import java.util.stream.Stream;

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
        List<String> loadedPacks
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
    }

    public static DatapackWorldgen load(Path packsDir, Path sessionDir, ResourceManager clientResources,
                                        DataFixer dataFixer, long seed) throws Exception {
        // 模板管理器需要一个 "generated" 目录；会话根目录放在 config/satella 内，EMT 之外不留文件。
        LevelStorage levelStorage = new LevelStorage(
            sessionDir.resolve("saves"), sessionDir.resolve("backups"),
            LevelStorage.createSymlinkFinder(sessionDir.resolve("allowed_symlinks.txt")), dataFixer);
        LevelStorage.Session templateSession = levelStorage.createSessionWithoutSymlinkCheck("satella-st");

        ResourcePackManager packManager = new ResourcePackManager(
            new VanillaDataPackProvider(levelStorage.getSymlinkFinder()),
            new FileResourcePackProvider(packsDir, ResourceType.SERVER_DATA, ResourcePackSource.WORLD, levelStorage.getSymlinkFinder()));
        Pair<DataConfiguration, LifecycledResourceManager> loaded =
            new SaveLoading.DataPacks(packManager, DataConfiguration.SAFE_MODE, false, false).load();
        LifecycledResourceManager resourceManager = loaded.getSecond();

        CombinedDynamicRegistries<ServerDynamicRegistryType> combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
        List<Registry.PendingTagLoad<?>> pendingTags = TagGroupLoader.startReload(
            resourceManager, combined.get(ServerDynamicRegistryType.STATIC));
        DynamicRegistryManager.Immutable preceding = combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN);
        List<RegistryWrapper.Impl<?>> wrappers = TagGroupLoader.collectRegistries(preceding, pendingTags);
        DynamicRegistryManager.Immutable dynamic = RegistryLoader.loadFromResource(resourceManager, wrappers, RegistryLoader.DYNAMIC_REGISTRIES);
        List<RegistryWrapper.Impl<?>> allWrappers = Stream.concat(wrappers.stream(), dynamic.stream()).toList();
        DynamicRegistryManager.Immutable dimensions = RegistryLoader.loadFromResource(resourceManager, allWrappers, RegistryLoader.DIMENSION_REGISTRIES);

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
            heightView, placementCalculator, templateManager, resourceManager, loadedPacks);
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
