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
                        if (!StLocator.hasSeed()) {
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
                .then(literal("reload")
                    .executes(ctx -> {
                        StLocator.reload();
                        ctx.getSource().sendFeedback(Component.literal("已重新加载数据包"));
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
        if (!StLocator.hasSeed()) {
            source.sendError(Component.literal("§c请先用 /st seed <种子> 设置种子"));
            return 0;
        }
        ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
        source.sendFeedback(Component.literal("§7正在搜索群系 " + biomeId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen();
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
        if (!StLocator.hasSeed()) {
            source.sendError(Component.literal("§c请先用 /st seed <种子> 设置种子"));
            return 0;
        }
        source.sendFeedback(Component.literal("§7正在搜索结构 " + structureId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen();
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

    private static CompletableFuture<Suggestions> suggestBiomes(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(builder, Registries.BIOME);
    }

    private static CompletableFuture<Suggestions> suggestStructures(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(builder, Registries.STRUCTURE);
    }

    private static CompletableFuture<Suggestions> suggestIds(SuggestionsBuilder builder, ResourceKey<? extends Registry<?>> key) {
        DatapackWorldgen wg = StLocator.peekWorldgen();
        if (wg == null) return builder.buildFuture();
        Registry<?> registry = wg.registryManager.lookupOrThrow(key);
        String remaining = builder.getRemaining().toLowerCase();
        for (Identifier id : registry.keySet()) {
            if (id.toString().startsWith(remaining)) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }
}
