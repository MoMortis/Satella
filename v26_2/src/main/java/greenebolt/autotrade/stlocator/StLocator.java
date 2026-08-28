package greenebolt.autotrade.stlocator;

import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.DataFixer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /st 定位器：纯客户端、离线的群系/结构查找。
 * 算法移植自 map.jacobsjo.eu（deepslate）并换用原版实现：
 *  - 群系：BiomeSource.findBiomeHorizontal 的螺旋搜索（32 格水平步长，6400 半径）；
 *  - 结构：结构集 placement 粗筛（RandomSpread 环形遍历 / ConcentricRings 预计算），
 *    候选区块再用 Structure.findValidGenerationPoint 精确判定。
 */
public final class StLocator {
    public static final Logger LOGGER = LoggerFactory.getLogger("satella-st");
    /** 同 LocateCommand 的结构搜索半径 */
    private static final int LOCATE_STRUCTURE_RADIUS = 100;

    private static volatile long seed;
    private static volatile boolean seedInitialized;
    private static volatile DatapackWorldgen worldgen;
    private static Path configDir;

    private StLocator() {}

    public static void init(Path satellaConfigDir) {
        configDir = satellaConfigDir;
        Path seedFile = satellaConfigDir.resolve("st-seed.txt");
        if (Files.isRegularFile(seedFile)) {
            try {
                setSeed(Long.parseLong(Files.readString(seedFile).trim()), false);
            } catch (Exception e) {
                LOGGER.warn("读取 st-seed.txt 失败", e);
            }
        }
    }

    public static boolean hasSeed() {
        return seedInitialized;
    }

    public static long getSeed() {
        return seed;
    }

    public static void setSeed(long newSeed, boolean persist) {
        seed = newSeed;
        seedInitialized = true;
        DatapackWorldgen old = worldgen;
        worldgen = null;
        if (old != null) old.close();
        if (persist && configDir != null) {
            try {
                Path f = configDir.resolve("st-seed.txt");
                Files.createDirectories(configDir);
                Files.writeString(f, Long.toString(newSeed));
            } catch (IOException e) {
                LOGGER.warn("写入 st-seed.txt 失败", e);
            }
        }
    }

    private static LevelStorageSource storage;
    private static LevelStorageSource.LevelStorageAccess templateSession;

    /**
     * 模板管理器所需会话，进程内只创建一次并持久持有：
     * session.lock 一旦释放就无法再次获取，重建会话必然撞锁。
     */
    private static synchronized LevelStorageSource.LevelStorageAccess session(DataFixer dataFixer) throws Exception {
        if (templateSession == null) {
            Path sessionRoot = configDir.resolve("st-session");
            DirectoryValidator validator = LevelStorageSource.parseValidator(sessionRoot.resolve("allowed_symlinks.txt"));
            storage = new LevelStorageSource(
                sessionRoot.resolve("saves"), sessionRoot.resolve("backups"), validator, dataFixer);
            templateSession = storage.createAccess("satella-st");
        }
        return templateSession;
    }

    /** 获取（必要时构建）世界生成栈；数据包 zip 变化时自动重建。 */
    public static DatapackWorldgen worldgen() throws Exception {
        DatapackWorldgen current = worldgen;
        if (current != null) return current;
        synchronized (StLocator.class) {
            if (worldgen != null) return worldgen;
            Path packsDir = configDir.resolve("datapacks");
            Files.createDirectories(packsDir);
            Minecraft client = Minecraft.getInstance();
            Path sessionRoot = configDir.resolve("st-session");
            DirectoryValidator validator = LevelStorageSource.parseValidator(sessionRoot.resolve("allowed_symlinks.txt"));
            DatapackWorldgen built = DatapackWorldgen.load(
                packsDir,
                validator,
                session(client.getFixerUpper()),
                client.getResourceManager(),
                client.getFixerUpper(),
                seed);
            worldgen = built;
            return built;
        }
    }

    public static void reload() {
        DatapackWorldgen old = worldgen;
        worldgen = null;
        if (old != null) old.close();
    }

    /** 供指令补全使用：已加载则返回，否则 null（不触发构建）。 */
    
    public static DatapackWorldgen peekWorldgen() {
        return worldgen;
    }

    /** 最近群系搜索，返回 null 表示半径内没找到。 */
    
    public static Pair<BlockPos, Holder<Biome>> findBiome(DatapackWorldgen worldgen, Identifier biomeId, BlockPos origin) {
        Registry<Biome> biomeRegistry = worldgen.registryManager.lookupOrThrow(Registries.BIOME);
        ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
        if (biomeRegistry.get(biomeKey).isEmpty()) {
            throw new IllegalArgumentException("未知群系: " + biomeId);
        }
        // 螺旋搜索（同 /locate biome 的步长与半径），在固定 Y 层采样
        int sampleY = Math.max(worldgen.heightView.getMinY(), Math.min(64, worldgen.heightView.getMaxY() - 1));
        return worldgen.biomeSource.findBiomeHorizontal(
            origin.getX(), sampleY, origin.getZ(), 6400, 32,
            holder -> holder.is(biomeKey),
            RandomSource.create(seed),
            true,
            worldgen.randomState.sampler());
    }

    /** 最近结构搜索，返回锚点位置；null 表示半径内没找到。 */
    
    public static BlockPos findStructure(DatapackWorldgen worldgen, Identifier structureId, BlockPos origin) {
        Registry<Structure> registry = worldgen.registryManager.lookupOrThrow(Registries.STRUCTURE);
        Holder<Structure> entry = registry.getOrThrow(ResourceKey.create(Registries.STRUCTURE, structureId));
        List<StructurePlacement> placements = worldgen.structureState.getPlacementsForStructure(entry);
        if (placements.isEmpty()) return null;
        worldgen.structureState.ensureStructuresGenerated();

        Structure structure = entry.value();
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (StructurePlacement placement : placements) {
            if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                // 要塞类：候选区块由同心环预计算直接给出
                List<ChunkPos> positions = worldgen.structureState.getRingPositionsFor(concentric);
                if (positions == null) continue;
                for (ChunkPos chunkPos : positions) {
                    BlockPos pos = checkStructureAt(worldgen, structure, placement, chunkPos);
                    if (pos != null) {
                        double d = pos.distSqr(origin);
                        if (d < bestDist) { bestDist = d; best = pos; }
                    }
                }
            } else if (placement instanceof RandomSpreadStructurePlacement spread) {
                // 网格式：按 spacing 为步长的区域环遍历，同 /locate 的 100 半径
                int spacing = spread.spacing();
                int baseX = Math.floorDiv(centerChunkX, spacing);
                int baseZ = Math.floorDiv(centerChunkZ, spacing);
                outer:
                for (int k = 0; k <= LOCATE_STRUCTURE_RADIUS; k++) {
                    for (int dx = -k; dx <= k; dx++) {
                        for (int dz = -k; dz <= k; dz++) {
                            if (Math.abs(dx) != k && Math.abs(dz) != k) continue;
                            ChunkPos start = spread.getPotentialStructureChunk(seed, baseX + dx, baseZ + dz);
                            BlockPos pos = checkStructureAt(worldgen, structure, placement, start);
                            if (pos != null) {
                                double d = pos.distSqr(origin);
                                if (d < bestDist) { bestDist = d; best = pos; }
                                // 更内环必定更近，命中后停止
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    
    private static BlockPos checkStructureAt(DatapackWorldgen worldgen, Structure structure, StructurePlacement placement, ChunkPos chunkPos) {
        if (!placement.isStructureChunk(worldgen.structureState, chunkPos.x(), chunkPos.z())) {
            return null;
        }
        Structure.GenerationContext context = new Structure.GenerationContext(
            worldgen.registryManager,
            worldgen.noiseGenerator,
            worldgen.biomeSource,
            worldgen.randomState,
            worldgen.templateManager,
            seed,
            chunkPos,
            worldgen.heightView,
            structure.biomes()::contains);
        return structure.findValidGenerationPoint(context)
            .map(stub -> {
                // findValidGenerationPoint 不一定应用群系判定，这里再确认一次锚点群系
                BlockPos p = stub.position();
                Holder<Biome> biome = worldgen.biomeSource.getNoiseBiome(
                    p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2, worldgen.randomState.sampler());
                return structure.biomes().contains(biome) ? placement.getLocatePos(chunkPos) : null;
            })
            .orElse(null);
    }
}
