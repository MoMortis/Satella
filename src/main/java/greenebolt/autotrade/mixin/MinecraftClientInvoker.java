package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTradeMinecraftClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientInvoker implements AutoTradeMinecraftClient {
    @Shadow
    private void doItemUse() {
    }

    @Override
    public void autoTrade$doItemUse() {
        this.doItemUse();
    }

}
