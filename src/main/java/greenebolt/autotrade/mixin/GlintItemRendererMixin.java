package greenebolt.autotrade.mixin;

import greenebolt.autotrade.GlintRenderLayer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.RenderLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemRenderer.class)
public abstract class GlintItemRendererMixin {
    @Redirect(method = {"getItemGlintConsumer", "getSpecialItemGlintConsumer"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;glint()Lnet/minecraft/client/render/RenderLayer;"))
    private static RenderLayer autoTrade$glint() { return GlintRenderLayer.glint(); }

    @Redirect(method = {"getItemGlintConsumer", "getSpecialItemGlintConsumer"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;glintTranslucent()Lnet/minecraft/client/render/RenderLayer;"))
    private static RenderLayer autoTrade$translucentGlint() { return GlintRenderLayer.translucentGlint(); }

    @Redirect(method = {"getItemGlintConsumer", "getSpecialItemGlintConsumer"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;entityGlint()Lnet/minecraft/client/render/RenderLayer;"))
    private static RenderLayer autoTrade$entityGlint() { return GlintRenderLayer.entityGlint(); }
}
