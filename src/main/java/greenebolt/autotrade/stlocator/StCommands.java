package greenebolt.autotrade.stlocator;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** 纯客户端 /st 指令：seed 设置、最近群系、最近结构。 */
public final class StCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("st")
                .then(literal("seed")
                    .executes(ctx -> {
                        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
                            ctx.getSource().sendError(StLocator.error("尚未设置种子，使用 /st seed <种子>"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(Text.literal("当前种子: " + StLocator.getSeed()));
                        return 1;
                    })
                    .then(argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> {
                            long seed = LongArgumentType.getLong(ctx, "seed");
                            StLocator.setSeed(seed, true);
                            StLocator.reload();
                            ctx.getSource().sendFeedback(Text.literal("种子已设置为 " + seed + "，世界生成栈已重建"));
                            return 1;
                        })))
                .then(literal("biome")
                    .then(argument("biome", IdentifierArgumentType.identifier())
                        .suggests(StCommands::suggestBiomes)
                        .executes(ctx -> executeLocateBiome(ctx.getSource(), ctx.getArgument("biome", Identifier.class)))))
                .then(literal("structure")
                    .then(argument("structure", IdentifierArgumentType.identifier())
                        .suggests(StCommands::suggestStructures)
                        .executes(ctx -> executeLocateStructure(ctx.getSource(), ctx.getArgument("structure", Identifier.class)))))
                .then(literal("anystructure")
                    .executes(ctx -> executeAnyStructure(ctx.getSource(), 5))
                    .then(argument("数量", IntegerArgumentType.integer(1, 10))
                        .executes(ctx -> executeAnyStructure(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "数量")))))
                .then(literal("anybiome")
                    .executes(ctx -> executeAnyBiome(ctx.getSource(), 8))
                    .then(argument("数量", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> executeAnyBiome(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "数量")))))
                .then(literal("rules")
                    .executes(ctx -> {
                        StLocator.LOGGER.info("打开多环定位规则编辑器");
                        // 从聊天栏执行时聊天框会在指令返回后 setScreen(null) 关闭自己，
                        // 直接开界面会被顶掉，延迟到下一拍再打开
                        MinecraftClient.getInstance().execute(() ->
                            MinecraftClient.getInstance().setScreen(new StRulesEditorScreen(null)));
                        return 1;
                    }))
                .then(literal("reload")
                    .executes(ctx -> {
                        StLocator.reload();
                        ctx.getSource().sendFeedback(Text.literal("§7正在重新加载数据包（全部数据包 + 默认种子）…"));
                        CompletableFuture.runAsync(() -> {
                            try {
                                send(source(ctx), packSummary(StLocator.worldgen(StLocator.getSeed(), null)));
                            } catch (Exception e) {
                                StLocator.LOGGER.error("加载数据包失败", e);
                                send(source(ctx), StLocator.error("加载失败: " + e.getMessage()));
                            }
                        });
                        return 1;
                    }))
        ));
    }

    private static BlockPos origin(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            return client.player.getBlockPos();
        }
        return BlockPos.ORIGIN;
    }

    private static int executeLocateBiome(FabricClientCommandSource source, Identifier biomeId) {
        RegistryKey<Biome> biomeKey = RegistryKey.of(RegistryKeys.BIOME, biomeId);
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Text.literal("§7" + sel.description() + "，正在搜索群系 " + biomeId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                var result = StLocator.findBiome(wg, biomeKey, origin(source));
                if (result == null) {
                    send(source, StLocator.error("6400 格范围内未找到 " + biomeId));
                    return;
                }
                BlockPos pos = result.getFirst();
                sendCoordinates(source, pos, biomeId.toString(), false);
                send(source, waypointMessage(StLocator.xaeroWaypoint(biomeId.toString(), pos)));
            } catch (Exception e) {
                StLocator.LOGGER.error("群系搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int executeLocateStructure(FabricClientCommandSource source, Identifier structureId) {
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Text.literal("§7" + sel.description() + "，正在搜索结构 " + structureId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                StLocator.StructureFindResult result = StLocator.findStructure(wg, structureId, origin(source));
                if (result.pos() == null) {
                    String reason;
                    if (result.placements() == 0) {
                        reason = "该结构不在任何已加载的结构集中";
                    } else {
                        reason = "扫描了 " + result.candidates() + " 个候选区块，群系校验失败 " + result.biomeFails()
                            + " 个（该结构要求的群系标签解析出 " + result.validBiomeCount() + " 项；若为 0 说明数据包标签未生效）";
                    }
                    send(source, StLocator.error("范围内未找到 " + structureId + "：" + reason));
                    return;
                }
                BlockPos pos = result.pos();
                sendCoordinates(source, pos, structureId.toString(), true);
                send(source, waypointMessage(StLocator.xaeroWaypoint(structureId.toString(), pos)));
            } catch (Exception e) {
                StLocator.LOGGER.error("结构搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int executeAnyStructure(FabricClientCommandSource source, int limit) {
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Text.literal("§7" + sel.description() + "，正在搜索最近的 " + limit + " 个结构 …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                List<StLocator.StructureHit> hits = StLocator.findNearestStructures(wg, origin(source), limit);
                if (hits.isEmpty()) {
                    send(source, StLocator.error("范围内未找到任何结构"));
                    return;
                }
                StringBuilder sb = new StringBuilder("§a最近的 " + hits.size() + " 个结构：");
                for (StLocator.StructureHit hit : hits) {
                    sb.append("\n§7- §f").append(hit.id())
                        .append(" §7@ §f").append(hit.pos().getX()).append(" ").append(hit.pos().getZ())
                        .append(" §7(距离 ").append(hit.distance()).append(" 格)");
                }
                send(source, Text.literal(sb.toString()));
                for (StLocator.StructureHit hit : hits) {
                    send(source, waypointMessage(StLocator.xaeroWaypoint(hit.id().toString(), hit.pos())));
                }
            } catch (Exception e) {
                StLocator.LOGGER.error("就近结构搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int executeAnyBiome(FabricClientCommandSource source, int limit) {
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Text.literal("§7" + sel.description() + "，正在搜索最近的 " + limit + " 种群系 …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                List<StLocator.BiomeHit> hits = StLocator.findNearestBiomes(wg, origin(source), limit);
                if (hits.isEmpty()) {
                    send(source, StLocator.error("范围内未找到任何群系"));
                    return;
                }
                StringBuilder sb = new StringBuilder("§a最近的 " + hits.size() + " 种群系：");
                for (StLocator.BiomeHit hit : hits) {
                    sb.append("\n§7- §f").append(hit.id())
                        .append(" §7@ §f").append(hit.pos().getX()).append(" ").append(hit.pos().getZ())
                        .append(" §7(距离 ").append(hit.distance()).append(" 格)");
                }
                send(source, Text.literal(sb.toString()));
                for (StLocator.BiomeHit hit : hits) {
                    send(source, waypointMessage(StLocator.xaeroWaypoint(hit.id().toString(), hit.pos())));
                }
            } catch (Exception e) {
                StLocator.LOGGER.error("就近群系搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    /** 航点串消息：点击即触发 Xaero 的导入指令（与 Xaero 自带导入按钮一致） */
    private static Text waypointMessage(String wp) {
        return Text.literal(wp).styled(style -> style
            .withColor(Formatting.GRAY)
            .withClickEvent(new ClickEvent.RunCommand(
                "/xaero_waypoint_add:" + wp.substring("xaero-waypoint:".length()))));
    }

    private static void sendCoordinates(FabricClientCommandSource source, BlockPos pos, String name, boolean clickTp) {
        Text coords = Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ())
            .styled(style -> style.withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击传到该坐标"))));
        send(source, Text.literal("§a最近的 " + name + ": §f").append(coords));
    }

    private static void send(FabricClientCommandSource source, Text message) {
        MinecraftClient.getInstance().execute(() -> source.sendFeedback(message));
    }

    /** 汇报本次加载启用的数据包与可查询的群系/结构数量 */
    private static Text packSummary(DatapackWorldgen wg) {
        int biomes = wg.registryManager.getOrThrow(RegistryKeys.BIOME).getIds().size();
        int structures = wg.registryManager.getOrThrow(RegistryKeys.STRUCTURE).getIds().size();
        StringBuilder sb = new StringBuilder("§a已启用 " + wg.loadedPacks.size() + " 个数据包，可查询 " + biomes + " 个群系 / " + structures + " 个结构：");
        for (String pack : wg.loadedPacks) {
            sb.append("\n§7- §f").append(pack);
        }
        return Text.literal(sb.toString());
    }

    private static FabricClientCommandSource source(CommandContext<FabricClientCommandSource> ctx) {
        return ctx.getSource();
    }

    private static CompletableFuture<Suggestions> suggestBiomes(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(context, builder, RegistryKeys.BIOME);
    }

    private static CompletableFuture<Suggestions> suggestStructures(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(context, builder, RegistryKeys.STRUCTURE);
    }

    private static CompletableFuture<Suggestions> suggestIds(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder, RegistryKey<? extends Registry<?>> key
    ) {
        String remaining = builder.getRemaining().toLowerCase();
        // 已加载离线世界生成栈（含 config/satella/datapacks 的数据包）时用它的注册表，
        // 否则回退到当前世界已同步的注册表，保证未加载时也能自动补全
        Iterable<Identifier> ids;
        if (StLocator.peekWorldgen() != null) {
            ids = StLocator.peekWorldgen().registryManager.getOrThrow((RegistryKey) key).getIds();
        } else {
            var client = MinecraftClient.getInstance();
            var registryManager = client.world != null ? client.world.getRegistryManager() : null;
            if (registryManager == null) return builder.buildFuture();
            Registry<?> registry = registryManager.getOrThrow((RegistryKey) key);
            ids = registry.getIds();
        }
        for (Identifier id : ids) {
            // 输入含命名空间时匹配完整 id，否则只按路径前缀匹配（"village" 能补出 "minecraft:village"）
            boolean match = remaining.indexOf(':') >= 0
                ? id.toString().startsWith(remaining)
                : id.getPath().startsWith(remaining);
            if (match) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }
}
