package greenebolt.autotrade;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.text.Text;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;

public final class AutoCraftController {
    private static int openCooldown;
    private static int craftTicker;

    private AutoCraftController() {}

    public static boolean isActive() {
        return AutoTradeConfigs.Trade.AUTO_CRAFTING.getBooleanValue();
    }

    public static void toggle() {
        boolean enabled = !AutoTradeConfigs.Trade.AUTO_CRAFTING.getBooleanValue();
        AutoTradeConfigs.Trade.AUTO_CRAFTING.setBooleanValue(enabled);
        if (!enabled) {
            autoTrade$closeHiddenCrafting(MinecraftClient.getInstance());
        }
        craftTicker = 0;
        openCooldown = 0;
        InfoUtils.sendVanillaMessage(Text.literal(enabled ? "全自动合成已开启" : "全自动合成已关闭")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED));
    }

    public static void tick(MinecraftClient mc) {
        if (!isActive() || mc.player == null || mc.world == null || mc.interactionManager == null) {
            craftTicker = 0;
            return;
        }

        // Refresh the same actionbar overlay used by the enable/disable messages.
        InfoUtils.sendVanillaMessage(Text.literal("全自动合成中...").formatted(Formatting.GREEN));

        if (mc.player.currentScreenHandler instanceof CraftingScreenHandler handler) {
            if (++craftTicker >= AutoTradeConfigs.Trade.AUTO_CRAFTING_INTERVAL.getIntegerValue()) {
                craftTicker = 0;
                autoTrade$craftHidden(handler, mc);
            }
            return;
        }

        craftTicker = 0;
        if (openCooldown > 0) {
            --openCooldown;
            return;
        }

        BlockPos table = autoTrade$findCraftingTable(mc);
        if (table != null) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(table), Direction.UP, table, false));
        }
        openCooldown = 10;
    }

    private static BlockPos autoTrade$findCraftingTable(MinecraftClient mc) {
        BlockPos center = mc.player.getBlockPos();
        double maxDistanceSquared = 4.5 * 4.5;
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!mc.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }
                    double distance = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance <= maxDistanceSquared && distance < closestDistance) {
                        closest = pos;
                        closestDistance = distance;
                    }
                }
            }
        }
        return closest;
    }

    private static void autoTrade$closeHiddenCrafting(MinecraftClient mc) {
        if (mc.player == null || !(mc.player.currentScreenHandler instanceof CraftingScreenHandler)) {
            return;
        }
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (networkHandler != null) {
            networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
        mc.player.currentScreenHandler.onClosed(mc.player);
        mc.player.currentScreenHandler = mc.player.playerScreenHandler;
    }

    private static void autoTrade$craftHidden(CraftingScreenHandler handler, MinecraftClient mc) {
        try {
            Class<?> callbacks = Class.forName("fi.dy.masa.itemscroller.event.KeybindCallbacks");
            var method = callbacks.getDeclaredMethod("autoTrade$craftHidden", CraftingScreenHandler.class, MinecraftClient.class);
            method.setAccessible(true);
            method.invoke(null, handler, mc);
        } catch (ReflectiveOperationException e) {
            AutoTrade.LOGGER.warn("Automatic crafting helper is unavailable", e);
        }
    }
}
