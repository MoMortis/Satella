package greenebolt.autotrade.mixin;

import greenebolt.autotrade.GlintRenderLayer;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.BufferAllocator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilderStorage.class)
public class GlintBufferBuilderStorageMixin {
    @Inject(method = "assignBufferBuilder", at = @At("HEAD"))
    private static void autoTrade$addGlintLayers(Object2ObjectLinkedOpenHashMap<RenderLayer, BufferAllocator> builders,
                                                   RenderLayer layer, CallbackInfo ci) {
        GlintRenderLayer.addGlintTypes(builders);
    }
}
