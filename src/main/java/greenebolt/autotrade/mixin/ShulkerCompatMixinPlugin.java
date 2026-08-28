package greenebolt.autotrade.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

public final class ShulkerCompatMixinPlugin implements IMixinConfigPlugin {
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.contains(".itemscroller.")) {
            if (mixinClassName.contains(".ipn.")) {
                return FabricLoader.getInstance().isModLoaded("inventoryprofilesnext");
            }
            if (mixinClassName.contains(".tweakeroo.")) {
                return FabricLoader.getInstance().isModLoaded("tweakeroo");
            }
            return true;
        }
        return FabricLoader.getInstance().isModLoaded("itemscroller");
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
