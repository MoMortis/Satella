package greenebolt.autotrade.stlocator;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /st 定位器：纯客户端、离线的群系/结构查找。
 * 算法移植自 map.jacobsjo.eu（deepslate）并换用原版实现：
 *  - 群系：BiomeSource.locateBiome 的螺旋搜索（32 格水平步长，64 格垂直步长，6400 半径）；
 *  - 结构：结构集 placement 粗筛（RandomSpread 环形遍历 / ConcentricRings 预计算），
 *    候选区块再用 Structure.getValidStructurePosition 精确判定。
 */
public final class StLocator {
    public static final Logger LOGGER = LoggerFactory.getLogger("satella-st");
    /** 同 LocateCommand.LOCATE_STRUCTURE_RADIUS */
    private static final int LOCATE_STRUCTURE_RADIUS = 100;

    /** 未命中环规则时使用的全局默认种子（/st seed 设置，持久化到 st-seed.txt） */
    private static volatile long seed;
    private static volatile boolean seedInitialized;
    private static Path configDir;
    /** (种子, 数据包子集) -> 世界生成栈缓存，各建一份互不影响 */
    private static final java.util.Map<WorldgenKey, DatapackWorldgen> worldgenCache =
        new java.util.LinkedHashMap<>();

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

    private static LevelStorage levelStorage;
    private static LevelStorage.Session templateSession;

    /**
     * 模板管理器所需会话，进程内只创建一次并持久持有：
     * session.lock 一旦释放就无法再次获取，重建会话必然撞锁。
     */
    private static synchronized LevelStorage.Session session(DataFixer dataFixer) throws Exception {
        if (templateSession == null) {
            Path sessionRoot = configDir.resolve("st-session");
            levelStorage = new LevelStorage(
                sessionRoot.resolve("saves"), sessionRoot.resolve("backups"),
                LevelStorage.createSymlinkFinder(sessionRoot.resolve("allowed_symlinks.txt")), dataFixer);
            templateSession = levelStorage.createSessionWithoutSymlinkCheck("satella-st");
        }
        return templateSession;
    }

    /** 是否配置了任何多环环规则（有规则时指令不再强制要求全局种子） */
    public static boolean hasRules() {
        return !parseRules(greenebolt.autotrade.AutoTradeConfigs.Trade.ST_RULES.getStrings()).isEmpty();
    }

    /** 单条多环规则：[min, max] 切比雪夫距离环 → 种子 + 数据包子集（空 = 全部） */
    public record StRule(int min, int max, long seed, List<String> packs) {} // packs: null=全部（省略数据包段），空=无，非空=指定列表

    /** 一次检索选中的配置 */
    public record Selected(long seed, List<String> packs, String description) {}

    private record WorldgenKey(long seed, boolean allPacks, List<String> packs) {}

    private static final int CACHE_LIMIT = 6;

    /** 解析 MaLiLib 里的多环规则字符串：最小-最大:种子[:数据包1|数据包2] */
    public static List<StRule> parseRules(List<String> entries) {
        List<StRule> rules = new ArrayList<>();
        for (String entry : entries) {
            String line = entry.trim();
            if (line.isEmpty()) continue;
            try {
                String[] parts = line.split(":", 3);
                String[] range = parts[0].split("-");
                int min = Integer.parseInt(range[0].trim());
                int max = Integer.parseInt(range[1].trim());
                long ruleSeed = Long.parseLong(parts[1].trim());
                List<String> packs = null; // 省略数据包段 = 全部
                if (parts.length >= 3) {
                    packs = new ArrayList<>();
                    for (String pack : parts[2].split("\\|")) {
                        String p = pack.trim();
                        if (!p.isEmpty()) {
                            packs.add(p);
                        }
                    }
                }
                rules.add(new StRule(min, max, ruleSeed, packs));
            } catch (Exception e) {
                LOGGER.warn("无法解析多环定位规则 \"{}\": {}", line, e.toString());
            }
        }
        return rules;
    }

    /**
     * 按检索中心到世界原点 (0,0) 的切比雪夫距离选择环规则；
     * 未命中任何环时回退到全局默认种子 + 全部数据包。
     */
    public static Selected select(BlockPos origin) {
        int dist = Math.max(Math.abs(origin.getX()), Math.abs(origin.getZ()));
        for (StRule rule : parseRules(greenebolt.autotrade.AutoTradeConfigs.Trade.ST_RULES.getStrings())) {
            if (dist >= rule.min() && dist <= rule.max()) {
                return new Selected(rule.seed(), rule.packs(),
                    "环 " + rule.min() + "-" + rule.max() + "（种子 " + rule.seed() + "，数据包 "
                        + packsDesc(rule.packs()) + "）");
            }
        }
        return new Selected(seed, List.of(),
            "默认配置（未命中环规则，种子 " + seed + "，数据包：无）");
    }

    /** 序列化一条规则为字符串（与 parseRules 互逆）：最小-最大:种子[:数据包1|数据包2] */
    public static String formatRule(StRule rule) {
        StringBuilder sb = new StringBuilder()
            .append(rule.min()).append('-').append(rule.max()).append(':').append(rule.seed());
        if (rule.packs() != null) {
            sb.append(':').append(String.join("|", rule.packs()));
        }
        return sb.toString();
    }

    /** 数据包描述文本 */
    private static String packsDesc(List<String> packs) {
        if (packs == null) return "全部";
        return packs.isEmpty() ? "无" : String.join("|", packs);
    }

    /** 获取（必要时构建）指定种子与数据包子集的世界生成栈，带缓存。
     * packs 为 null 表示全部数据包，空列表表示不加载任何数据包 zip。 */
    public static DatapackWorldgen worldgen(long seed, @Nullable List<String> packs) throws Exception {
        boolean allPacks = packs == null;
        List<String> keyPacks = allPacks ? List.of() : packs.stream().map(String::trim).sorted().toList();
        WorldgenKey key = new WorldgenKey(seed, allPacks, keyPacks);
        synchronized (worldgenCache) {
            DatapackWorldgen cached = worldgenCache.get(key);
            if (cached != null) {
                // LRU 触碰
                worldgenCache.remove(key);
                worldgenCache.put(key, cached);
                return cached;
            }
        }
        Path packsDir = configDir.resolve("datapacks");
        Files.createDirectories(packsDir);
        MinecraftClient client = MinecraftClient.getInstance();
        DatapackWorldgen built = DatapackWorldgen.load(
            packsDir,
            levelStorage != null ? levelStorage.getSymlinkFinder()
                : LevelStorage.createSymlinkFinder(configDir.resolve("st-session/allowed_symlinks.txt")),
            session(client.getDataFixer()),
            client.getResourceManager(),
            client.getDataFixer(),
            seed,
            packs);
        synchronized (worldgenCache) {
            worldgenCache.put(key, built);
            while (worldgenCache.size() > CACHE_LIMIT) {
                WorldgenKey oldest = worldgenCache.keySet().iterator().next();
                DatapackWorldgen evicted = worldgenCache.remove(oldest);
                if (evicted != null) evicted.close();
            }
        }
        return built;
    }

    public static void reload() {
        synchronized (worldgenCache) {
            for (DatapackWorldgen worldgen : worldgenCache.values()) {
                worldgen.close();
            }
            worldgenCache.clear();
        }
    }

    /** 供指令补全使用：任一已缓存的世界生成栈，没有则 null（不触发构建）。 */
    @Nullable
    public static DatapackWorldgen peekWorldgen() {
        synchronized (worldgenCache) {
            return worldgenCache.values().stream().findFirst().orElse(null);
        }
    }

    /** 最近群系搜索，返回 null 表示半径内没找到。 */
    @Nullable
    public static Pair<BlockPos, RegistryEntry<Biome>> findBiome(DatapackWorldgen worldgen, RegistryKey<Biome> biomeKey, BlockPos origin) {
        Registry<Biome> biomeRegistry = worldgen.registryManager.getOrThrow(RegistryKeys.BIOME);
        Biome targetBiome = biomeRegistry.get(biomeKey);
        if (targetBiome == null) {
            throw new IllegalArgumentException("未知群系: " + biomeKey.getValue());
        }
        // 螺旋搜索（同 /locate biome 的步长与半径），在固定 Y 层采样（结构化噪声群系按气候匹配）
        int sampleY = Math.max(worldgen.heightView.getBottomY(), Math.min(64, worldgen.heightView.getTopYInclusive()));
        return worldgen.biomeSource.locateBiome(
            origin.getX(), sampleY, origin.getZ(), 6400, 32,
            entry -> entry.value() == targetBiome,
            net.minecraft.util.math.random.Random.create(worldgen.seed),
            true,
            worldgen.noiseConfig.getMultiNoiseSampler());
    }

    /** 最近结构搜索，返回锚点位置；null 表示半径内没找到。 */
    @Nullable
    public static BlockPos findStructure(DatapackWorldgen worldgen, Identifier structureId, BlockPos origin) {
        Registry<Structure> registry = worldgen.registryManager.getOrThrow(RegistryKeys.STRUCTURE);
        Structure structureValue = registry.get(RegistryKey.of(RegistryKeys.STRUCTURE, structureId));
        if (structureValue == null) {
            throw new IllegalArgumentException("未知结构: " + structureId);
        }
        RegistryEntry<Structure> entry = registry.getEntry(structureValue);
        List<StructurePlacement> placements = worldgen.placementCalculator.getPlacements(entry);
        if (placements.isEmpty()) {
            LOGGER.info("结构搜索 {}: placements 为空（结构集被群系过滤或未注册），biomeSource.getBiomes() 数量={}",
                structureId, worldgen.biomeSource.getBiomes().size());
            return null;
        }

        Structure structure = entry.value();
        int centerChunkX = origin.getX() >> 4;
        int centerChunkZ = origin.getZ() >> 4;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int[] diag = new int[4]; // 0=候选数 1=shouldGenerate通过 2=位置判定通过 3=群系失败

        for (StructurePlacement placement : placements) {
            if (placement instanceof ConcentricRingsStructurePlacement concentric) {
                // 要塞类：候选区块由同心环预计算直接给出
                List<ChunkPos> positions = worldgen.placementCalculator.getPlacementPositions(concentric);
                if (positions == null) continue;
                for (ChunkPos chunkPos : positions) {
                    diag[0]++;
                    BlockPos pos = checkStructureAt(worldgen, structure, placement, chunkPos, diag);
                    if (pos != null) {
                        double d = pos.getSquaredDistance(origin);
                        if (d < bestDist) { bestDist = d; best = pos; }
                    }
                }
            } else if (placement instanceof RandomSpreadStructurePlacement spread) {
                // 网格式：按 spacing 为步长的区域环遍历，同 /locate 的 100 半径
                // 注意 getStartChunk 的入参是区块坐标（内部按 spacing 取模），与原版
                // ChunkGenerator.locateRandomSpreadStructure 一致：中心区块 + spacing*环偏移
                int spacing = spread.getSpacing();
                outer:
                for (int k = 0; k <= LOCATE_STRUCTURE_RADIUS; k++) {
                    for (int dx = -k; dx <= k; dx++) {
                        for (int dz = -k; dz <= k; dz++) {
                            if (Math.abs(dx) != k && Math.abs(dz) != k) continue;
                            ChunkPos start = spread.getStartChunk(worldgen.seed, centerChunkX + spacing * dx, centerChunkZ + spacing * dz);
                            diag[0]++;
                            BlockPos pos = checkStructureAt(worldgen, structure, placement, start, diag);
                            if (pos != null) {
                                double d = pos.getSquaredDistance(origin);
                                if (d < bestDist) { bestDist = d; best = pos; }
                                // 同一环内可能有多个候选，但更内环必定更近，可以停在最内命中环
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        LOGGER.info("结构搜索 {}: seed={}, placements={}, 候选={}, shouldGenerate 通过={}, 位置+群系判定通过={}, 群系失败={}",
            structureId, worldgen.seed, placements.size(), diag[0], diag[1], diag[2], diag[3]);
        return best;
    }

    @Nullable
    private static BlockPos checkStructureAt(DatapackWorldgen worldgen, Structure structure, StructurePlacement placement, ChunkPos chunkPos, int[] diag) {
        if (!placement.shouldGenerate(worldgen.placementCalculator, chunkPos.x, chunkPos.z)) {
            return null;
        }
        diag[1]++;
        Structure.Context context = new Structure.Context(
            worldgen.registryManager,
            worldgen.noiseGenerator,
            worldgen.biomeSource,
            worldgen.noiseConfig,
            worldgen.templateManager,
            worldgen.seed,
            chunkPos,
            worldgen.heightView,
            structure.getValidBiomes()::contains);
        Optional<Structure.StructurePosition> position = structure.getValidStructurePosition(context);
        if (position.isPresent()) {
            diag[2]++;
            return placement.getLocatePos(chunkPos);
        }
        diag[3]++;
        return null;
    }

    public static Text error(String message) {
        return Text.literal("§c" + message);
    }
}
