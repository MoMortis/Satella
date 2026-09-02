package greenebolt.autotrade;

import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class AutoCraftController {
    private static int openCooldown;
    private static int craftCooldown;

    private AutoCraftController() {}

    public static boolean isActive() {
        return AutoTradeConfigs.Trade.AUTO_CRAFTING.getBooleanValue();
    }

    public static void toggle() {
        boolean enabled = !AutoTradeConfigs.Trade.AUTO_CRAFTING.getBooleanValue();
        AutoTradeConfigs.Trade.AUTO_CRAFTING.setBooleanValue(enabled);
        if (!enabled) close(Minecraft.getInstance());
        openCooldown = 0;
        craftCooldown = 0;
        InfoUtils.sendVanillaMessage(Component.literal(enabled ? "全自动合成已开启" : "全自动合成已关闭")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    public static void tick(Minecraft minecraft) {
        if (!isActive() || minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            craftCooldown = 0;
            return;
        }
        if (minecraft.player.containerMenu instanceof CraftingMenu menu) {
            if (++craftCooldown >= AutoTradeConfigs.Trade.AUTO_CRAFTING_INTERVAL.getIntegerValue()) {
                craftCooldown = 0;
                ResidualCrafting.craft(menu, minecraft, 1, 9, 1);
            }
            return;
        }
        craftCooldown = 0;
        if (openCooldown-- > 0) return;
        BlockPos table = findTable(minecraft);
        if (table != null) minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(table), Direction.UP, table, false));
        openCooldown = 10;
    }

    private static BlockPos findTable(Minecraft minecraft) {
        BlockPos center = minecraft.player.blockPosition();
        BlockPos best = null;
        double bestDistance = 4.5 * 4.5;
        for (int x = -4; x <= 4; x++) for (int y = -4; y <= 4; y++) for (int z = -4; z <= 4; z++) {
            BlockPos pos = center.offset(x, y, z);
            if (!minecraft.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double distance = minecraft.player.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance <= bestDistance) { best = pos; bestDistance = distance; }
        }
        return best;
    }

    private static void close(Minecraft minecraft) {
        if (minecraft.player == null || !(minecraft.player.containerMenu instanceof CraftingMenu)) return;
        if (minecraft.getConnection() != null) minecraft.getConnection().getConnection()
                .send(new ServerboundContainerClosePacket(minecraft.player.containerMenu.containerId));
        minecraft.player.containerMenu.removed(minecraft.player);
        minecraft.player.containerMenu = minecraft.player.inventoryMenu;
    }
}
