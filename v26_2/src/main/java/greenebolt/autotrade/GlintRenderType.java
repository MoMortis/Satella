package greenebolt.autotrade;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Map;

public final class GlintRenderType {
    private static final Map<GlintPreset, RenderType> GLINT = new EnumMap<>(GlintPreset.class);
    private static final Map<GlintPreset, RenderType> TRANSLUCENT = new EnumMap<>(GlintPreset.class);
    private static final Map<GlintPreset, RenderType> ARMOR_GLINT = new EnumMap<>(GlintPreset.class);

    static {
        for (GlintPreset preset : GlintPreset.values()) {
            GLINT.put(preset, build("glint_" + preset.getStringValue(), preset, TextureTransform.GLINT_TEXTURING, null));
            TRANSLUCENT.put(preset, build("glint_translucent_" + preset.getStringValue(), preset,
                    TextureTransform.GLINT_TEXTURING, net.minecraft.client.renderer.rendertype.OutputTarget.ITEM_ENTITY_TARGET));
            ARMOR_GLINT.put(preset, build("armor_glint_" + preset.getStringValue(), preset,
                    TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING, null));
        }
    }

    private GlintRenderType() {}

    public static RenderType glint() { return selected(GLINT, RenderTypes.glint()); }
    public static RenderType translucent() { return selected(TRANSLUCENT, RenderTypes.glintTranslucent()); }
    public static RenderType armorGlint() { return selected(ARMOR_GLINT, RenderTypes.armorEntityGlint()); }

    private static RenderType selected(Map<GlintPreset, RenderType> layers, RenderType fallback) {
        GlintPreset preset = (GlintPreset) AutoTradeConfigs.Trade.ENCHANTMENT_COLOR.getOptionListValue();
        return layers.getOrDefault(preset, fallback);
    }

    private static RenderType build(String name, GlintPreset preset, TextureTransform transform,
                                    net.minecraft.client.renderer.rendertype.OutputTarget target) {
        Identifier texture = Identifier.fromNamespaceAndPath("satella", "textures/misc/" + preset.textureName() + ".png");
        RenderSetup.RenderSetupBuilder builder = RenderSetup.builder(RenderPipelines.GLINT)
                .withTexture("Sampler0", texture)
                .setTextureTransform(transform);
        if (transform == TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING) {
            builder.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING);
        }
        if (target != null) builder.setOutputTarget(target);
        return greenebolt.autotrade.mixin.RenderTypeInvoker.autoTrade$create(name, builder.createRenderSetup());
    }
}
