package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.AutoCraftController;
import greenebolt.autotrade.AutoStonecutController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, remap = false)
public class ScreenOpenMixin {
    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoTrade$hideMerchant(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        // “GUI显示”开启时不拦截，工作台/切石机界面正常渲染；自动交易始终隐藏村民界面
        boolean hideAutomationGui = !AutoTradeConfigs.Trade.GUI_DISPLAY.getBooleanValue();
        boolean merchant = AutoTradeConfigs.isEnabled() && packet.getType() == MenuType.MERCHANT;
        boolean crafting = hideAutomationGui && AutoCraftController.isActive() && packet.getType() == MenuType.CRAFTING;
        boolean stonecutting = hideAutomationGui && AutoStonecutController.isActive() && packet.getType() == MenuType.STONECUTTER;
        if (!merchant && !crafting && !stonecutting) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.player.containerMenu = packet.getType().create(packet.getContainerId(), minecraft.player.getInventory());
        ci.cancel();
    }
}
