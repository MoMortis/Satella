package greenebolt.autotrade;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.TextureTransform;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Map;

public final class GlintRenderLayer {
    private static final Map<GlintPreset, RenderLayer> GLINT = new EnumMap<>(GlintPreset.class);
    private static final Map<GlintPreset, RenderLayer> ENTITY_GLINT = new EnumMap<>(GlintPreset.class);
    private static final Map<GlintPreset, RenderLayer> TRANSLUCENT = new EnumMap<>(GlintPreset.class);
    private static final Map<GlintPreset, RenderLayer> ARMOR_GLINT = new EnumMap<>(GlintPreset.class);

    static {
        for (GlintPreset preset : GlintPreset.values()) {
            GLINT.put(preset, build("glint_" + preset.getStringValue(), preset, RenderPipelines.GLINT,
                    TextureTransform.GLINT_TEXTURING, null));
            ENTITY_GLINT.put(preset, build("entity_glint_" + preset.getStringValue(), preset, RenderPipelines.GLINT,
                    TextureTransform.ENTITY_GLINT_TEXTURING, null));
            TRANSLUCENT.put(preset, build("glint_translucent_" + preset.getStringValue(), preset, RenderPipelines.GLINT,
                    TextureTransform.GLINT_TEXTURING, net.minecraft.client.render.OutputTarget.ITEM_ENTITY_TARGET));
            ARMOR_GLINT.put(preset, build("armor_glint_" + preset.getStringValue(), preset, RenderPipelines.GLINT,
                    TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING, null));
        }
    }

    private GlintRenderLayer() {}

    public static RenderLayer glint() { return selected(GLINT, RenderLayers.glint()); }
    public static RenderLayer entityGlint() { return selected(ENTITY_GLINT, RenderLayers.entityGlint()); }
    public static RenderLayer translucentGlint() { return selected(TRANSLUCENT, RenderLayers.glintTranslucent()); }
    public static RenderLayer armorGlint() { return selected(ARMOR_GLINT, RenderLayers.armorEntityGlint()); }

    private static RenderLayer selected(Map<GlintPreset, RenderLayer> layers, RenderLayer fallback) {
        GlintPreset preset = (GlintPreset) AutoTradeConfigs.Trade.ENCHANTMENT_COLOR.getOptionListValue();
        return layers.getOrDefault(preset, fallback);
    }

    private static RenderLayer build(String name, GlintPreset preset, Object pipeline, Object transform, Object target) {
        var builder = RenderSetup.builder((com.mojang.blaze3d.pipeline.RenderPipeline) pipeline)
                .texture("Sampler0", Identifier.of("satella", "textures/misc/" + preset.textureName() + ".png"))
                .textureTransform((TextureTransform) transform);
        if (transform == TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING) {
            builder.layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING);
        }
        if (target != null) builder.outputTarget((net.minecraft.client.render.OutputTarget) target);
        return RenderLayer.of(name, builder.build());
    }

    public static void addGlintTypes(Object2ObjectLinkedOpenHashMap<RenderLayer, BufferAllocator> builders) {
        addAll(builders, GLINT);
        addAll(builders, ENTITY_GLINT);
        addAll(builders, TRANSLUCENT);
        addAll(builders, ARMOR_GLINT);
    }

    private static void addAll(Object2ObjectLinkedOpenHashMap<RenderLayer, BufferAllocator> builders,
                               Map<GlintPreset, RenderLayer> layers) {
        for (RenderLayer layer : layers.values()) {
            builders.computeIfAbsent(layer, value -> new BufferAllocator(layer.getExpectedBufferSize()));
        }
    }
}
