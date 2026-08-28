package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.AutoCraftController;
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
        boolean merchant = AutoTradeConfigs.isEnabled() && packet.getType() == MenuType.MERCHANT;
        boolean crafting = AutoCraftController.isActive() && packet.getType() == MenuType.CRAFTING;
        if (!merchant && !crafting) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        minecraft.player.containerMenu = packet.getType().create(packet.getContainerId(), minecraft.player.getInventory());
        ci.cancel();
    }
}
