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
                        if (!StLocator.hasSeed()) {
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
                .then(literal("reload")
                    .executes(ctx -> {
                        StLocator.reload();
                        ctx.getSource().sendFeedback(Text.literal("已重新加载数据包"));
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
        if (!StLocator.hasSeed()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置种子"));
            return 0;
        }
        source.sendFeedback(Text.literal("§7正在搜索群系 " + biomeId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen();
                var result = StLocator.findBiome(wg, biomeKey, origin(source));
                if (result == null) {
                    send(source, StLocator.error("6400 格范围内未找到 " + biomeId));
                    return;
                }
                BlockPos pos = result.getFirst();
                sendCoordinates(source, pos, biomeId.toString(), false);
            } catch (Exception e) {
                StLocator.LOGGER.error("群系搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
    }

    private static int executeLocateStructure(FabricClientCommandSource source, Identifier structureId) {
        if (!StLocator.hasSeed()) {
            source.sendError(StLocator.error("请先用 /st seed <种子> 设置种子"));
            return 0;
        }
        source.sendFeedback(Text.literal("§7正在搜索结构 " + structureId + " …"));
        CompletableFuture.runAsync(() -> {
            try {
                DatapackWorldgen wg = StLocator.worldgen();
                BlockPos pos = StLocator.findStructure(wg, structureId, origin(source));
                if (pos == null) {
                    send(source, StLocator.error("范围内未找到 " + structureId + "（结构不存在、不在任何结构集中，或附近群系不匹配）"));
                    return;
                }
                sendCoordinates(source, pos, structureId.toString(), true);
            } catch (Exception e) {
                StLocator.LOGGER.error("结构搜索失败", e);
                send(source, StLocator.error("搜索失败: " + e.getMessage()));
            }
        });
        return 1;
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

    private static CompletableFuture<Suggestions> suggestBiomes(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(context, builder, RegistryKeys.BIOME);
    }

    private static CompletableFuture<Suggestions> suggestStructures(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        return suggestIds(context, builder, RegistryKeys.STRUCTURE);
    }

    private static CompletableFuture<Suggestions> suggestIds(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder, RegistryKey<? extends Registry<?>> key
    ) {
        DatapackWorldgen wg = StLocator.peekWorldgen();
        if (wg == null) return builder.buildFuture();
        Registry<?> registry = wg.registryManager.getOrThrow((RegistryKey) key);
        String remaining = builder.getRemaining().toLowerCase();
        for (Identifier id : registry.getIds()) {
            if (id.toString().startsWith(remaining)) {
                builder.suggest(id.toString());
            }
        }
        return builder.buildFuture();
    }
}
