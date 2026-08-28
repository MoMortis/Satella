package greenebolt.autotrade.stlocator;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** 纯客户端 /st 指令：seed 设置、最近群系、最近结构。 */
public final class StCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            literal("st")
                .then(literal("seed")
                    .executes(ctx -> {
                        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
                            ctx.getSource().sendError(Component.literal("§c尚未设置种子，使用 /st seed <种子>"));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(Component.literal("当前种子: " + StLocator.getSeed()));
                        return 1;
                    })
                    .then(argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> {
                            long seed = LongArgumentType.getLong(ctx, "seed");
                            StLocator.setSeed(seed, true);
                            StLocator.reload();
                            ctx.getSource().sendFeedback(Component.literal("种子已设置为 " + seed + "，世界生成栈已重建"));
                            return 1;
                        })))
                .then(literal("biome")
                    .then(argument("biome", IdentifierArgument.id())
                        .suggests(StCommands::suggestBiomes)
                        .executes(ctx -> executeLocateBiome(ctx.getSource(), ctx.getArgument("biome", Identifier.class)))))
                .then(literal("structure")
                    .then(argument("structure", IdentifierArgument.id())
                        .suggests(StCommands::suggestStructures)
                        .executes(ctx -> executeLocateStructure(ctx.getSource(), ctx.getArgument("structure", Identifier.class)))))
                .then(literal("rules")
                    .executes(ctx -> {
                        Minecraft.getInstance().setScreenAndShow(new StRulesEditorScreen(null));
                        return 1;
                    }))
                .then(literal("reload")
                    .executes(ctx -> {
                        StLocator.reload();
                        ctx.getSource().sendFeedback(Component.literal("§7正在重新加载数据包（全部数据包 + 默认种子）…"));
                        CompletableFuture.runAsync(() -> {
                            try {
                                send(ctx.getSource(), packSummary(StLocator.worldgen(StLocator.getSeed(), null)));
                            } catch (Exception e) {
                                StLocator.LOGGER.error("加载数据包失败", e);
                                send(ctx.getSource(), Component.literal("§c加载失败: " + e.getMessage()));
                            }
                        });
                        return 1;
                    }))
        ));
    }

    private static BlockPos origin(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            return client.player.blockPosition();
        }
        return BlockPos.ZERO;
    }

    private static int executeLocateBiome(FabricClientCommandSource source, Identifier biomeId) {
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(Component.literal("§c请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Component.literal("§7" + sel.description() + "，正在搜索群系 " + biomeId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                var result = StLocator.findBiome(wg, biomeId, origin(source));
                if (result == null) {
                    send(source, Component.literal("§c6400 格范围内未找到 " + biomeId));
                    return;
                }
                BlockPos pos = result.getFirst();
                sendCoordinates(source, pos, biomeId.toString());
            } catch (Exception e) {
                StLocator.LOGGER.error("群系搜索失败", e);
                send(source, Component.literal("§c搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int executeLocateStructure(FabricClientCommandSource source, Identifier structureId) {
        if (!StLocator.hasSeed() && !StLocator.hasRules()) {
            source.sendError(Component.literal("§c请先用 /st seed <种子> 设置默认种子（环规则里的种子不受影响）"));
            return 0;
        }
        StLocator.Selected sel = StLocator.select(origin(source));
        source.sendFeedback(Component.literal("§7" + sel.description() + "，正在搜索结构 " + structureId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen(sel.seed(), sel.packs());
                BlockPos pos = StLocator.findStructure(wg, structureId, origin(source));
                if (pos == null) {
                    send(source, Component.literal("§c范围内未找到 " + structureId + "（结构不存在、不在任何结构集中，或附近群系不匹配）"));
                    return;
                }
                sendCoordinates(source, pos, structureId.toString());
            } catch (Exception e) {
                StLocator.LOGGER.error("结构搜索失败", e);
                send(source, Component.literal("§c搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static void sendCoordinates(FabricClientCommandSource source, BlockPos pos, String name) {
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Component coordsText = Component.literal(coords)
            .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN));
        send(source, Component.literal("§a最近的 " + name + ": §f").append(coordsText));
    }

    private static void send(FabricClientCommandSource source, Component message) {
        Minecraft.getInstance().execute(() -> source.sendFeedback(message));
    }

    /** 汇报本次加载启用的数据包与可查询的群系/结构数量 */
    private static Component packSummary(DatapackWorldgen wg) {
        int biomes = wg.registryManager.lookupOrThrow(Registries.BIOME).keySet().size();
        int structures = wg.registryManager.lookupOrThrow(Registries.STRUCTURE).keySet().size();
        StringBuilder sb = new StringBuilder("§a已启用 " + wg.loadedPacks.size() + " 个数据包，可查询 " + biomes + " 个群系 / " + structures + " 个结构：");
        for (String pack : wg.loadedPacks) {
            sb.append("\n§7- §f").append(pack);
        }
        return Component.literal(sb.toString());
    }

    private static CompletableFuture<Suggestions> suggestBiomes(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(builder, Registries.BIOME);
    }

    private static CompletableFuture<Suggestions> suggestStructures(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(builder, Registries.STRUCTURE);
    }

    private static CompletableFuture<Suggestions> suggestIds(SuggestionsBuilder builder, ResourceKey<? extends Registry<?>> key) {
        String remaining = builder.getRemaining().toLowerCase();
        // 已加载离线世界生成栈（含 config/satella/datapacks 的数据包）时用它的注册表，
        // 否则回退到当前世界已同步的注册表，保证未加载时也能自动补全
        Iterable<Identifier> ids;
        DatapackWorldgen wg = StLocator.peekWorldgen();
        if (wg != null) {
            ids = wg.registryManager.lookupOrThrow(key).keySet();
        } else {
            var client = Minecraft.getInstance();
            var registryAccess = client.level != null ? client.level.registryAccess() : null;
            if (registryAccess == null) return builder.buildFuture();
            ids = registryAccess.lookupOrThrow(key).keySet();
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
