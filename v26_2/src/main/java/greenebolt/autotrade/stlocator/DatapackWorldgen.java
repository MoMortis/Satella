package greenebolt.autotrade.stlocator;

import com.mojang.datafixers.DataFixer;
import net.minecraft.util.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.nio.file.Path;

/**
 * 纯客户端世界生成栈：通过 {@link WorldLoader} 加载原版数据包与
 * config/satella/datapacks 下的数据包 zip，解析全部动态注册表，并实例化
 * overworld 噪声生成器，使任意种子下的群系/结构查询可以完全离线执行。
 * 会话目录位于 config/satella 内，EMT 之外不留文件。
 */
public final class DatapackWorldgen implements AutoCloseable {
    public final RegistryAccess.Frozen registryManager;
    public final NoiseBasedChunkGenerator noiseGenerator;
    public final RandomState randomState;
    public final BiomeSource biomeSource;
    public final LevelHeightAccessor heightView;
    public final ChunkGeneratorStructureState structureState;
    public final StructureTemplateManager templateManager;
    private final CloseableResourceManager resources;
    private final LevelStorageSource.LevelStorageAccess session;

    private DatapackWorldgen(
        RegistryAccess.Frozen registryManager,
        NoiseBasedChunkGenerator noiseGenerator,
        RandomState randomState,
        BiomeSource biomeSource,
        LevelHeightAccessor heightView,
        ChunkGeneratorStructureState structureState,
        StructureTemplateManager templateManager,
        CloseableResourceManager resources,
        LevelStorageSource.LevelStorageAccess session
    ) {
        this.registryManager = registryManager;
        this.noiseGenerator = noiseGenerator;
        this.randomState = randomState;
        this.biomeSource = biomeSource;
        this.heightView = heightView;
        this.structureState = structureState;
        this.templateManager = templateManager;
        this.resources = resources;
        this.session = session;
    }

    private record Loaded(
        HolderLookup.Provider worldgen,
        RegistryAccess.Frozen dims,
        CloseableResourceManager resources,
        LevelStorageSource.LevelStorageAccess session
    ) {}

    public static DatapackWorldgen load(Path configDir, Path packsDir, Path sessionRoot,
                                        ResourceManager clientResources, DataFixer dataFixer, long seed) throws Exception {
        DirectoryValidator validator = LevelStorageSource.parseValidator(sessionRoot.resolve("allowed_symlinks.txt"));
        LevelStorageSource storage = new LevelStorageSource(
            sessionRoot.resolve("saves"), sessionRoot.resolve("backups"), validator, dataFixer);
        LevelStorageSource.LevelStorageAccess session = storage.createAccess("satella-st");

        PackRepository repo = ServerPacksSource.createPackRepository(packsDir, validator);
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(repo, WorldDataConfiguration.DEFAULT, false, false);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED, PermissionSet.NO_PERMISSIONS);

        Loaded loaded = WorldLoader.load(
            init,
            ctx -> new WorldLoader.DataLoadOutput<>(
                new Loaded(ctx.datapackWorldgen(), ctx.datapackDimensions(), null, session), ctx.datapackDimensions()),
            (rm, serverResources, registryAccess, result) ->
                new Loaded(result.worldgen(), result.dims(), rm, result.session()),
            Util.backgroundExecutor(), Util.backgroundExecutor()
        ).get();

        LevelStem stem = loaded.dims().lookupOrThrow(Registries.LEVEL_STEM)
            .get(LevelStem.OVERWORLD).map(Holder::value).orElse(null);
        NoiseBasedChunkGenerator generator = null;
        if (stem != null && stem.generator() instanceof NoiseBasedChunkGenerator noiseGen) {
            generator = noiseGen;
        } else {
            for (LevelStem entry : loaded.dims().lookupOrThrow(Registries.LEVEL_STEM)) {
                if (entry.generator() instanceof NoiseBasedChunkGenerator noiseGen) {
                    generator = noiseGen;
                    break;
                }
            }
        }
        if (generator == null) {
            throw new IllegalStateException("数据包中没有基于噪声的维度");
        }

        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        RandomState randomState = RandomState.create(settings, loaded.worldgen().lookupOrThrow(Registries.NOISE), seed);
        LevelHeightAccessor heightView = LevelHeightAccessor.create(settings.noiseSettings().minY(), settings.noiseSettings().height());
        ChunkGeneratorStructureState structureState = ChunkGeneratorStructureState.createForNormal(
            randomState, seed, generator.getBiomeSource(), loaded.dims().lookupOrThrow(Registries.STRUCTURE_SET));
        StructureTemplateManager templateManager = new StructureTemplateManager(
            clientResources, session, dataFixer, loaded.dims().lookupOrThrow(Registries.BLOCK));

        return new DatapackWorldgen(loaded.dims(), generator, randomState, generator.getBiomeSource(),
            heightView, structureState, templateManager, loaded.resources(), session);
    }

    @Override
    public void close() {
        if (resources != null) resources.close();
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
