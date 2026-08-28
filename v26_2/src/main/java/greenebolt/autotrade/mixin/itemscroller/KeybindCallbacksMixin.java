package greenebolt.autotrade.mixin.itemscroller;

import fi.dy.masa.itemscroller.event.KeybindCallbacks;
import fi.dy.masa.itemscroller.recipes.CraftingHandler;
import fi.dy.masa.malilib.util.GuiUtils;
import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.ResidualCrafting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeybindCallbacks.class, remap = false)
public abstract class KeybindCallbacksMixin {
    @Shadow protected int massCraftTicker;

    @Inject(method = "onClientTickMassCraftImpl", at = @At("HEAD"), cancellable = true, remap = false)
    private void autoTrade$residualCraft(Minecraft minecraft, CallbackInfo ci) {
        if (!AutoTradeConfigs.Trade.RESIDUAL_CRAFTING.getBooleanValue() || minecraft.player == null
                || !(GuiUtils.getCurrentScreen() instanceof AbstractContainerScreen<?> screen)
                || !keysDown(minecraft)) return;
        ci.cancel();
        if (++massCraftTicker < 2) return;
        massCraftTicker = 0;
        Slot output = CraftingHandler.getFirstCraftingOutputSlotForGui(screen);
        CraftingHandler.SlotRange range = output == null ? null : CraftingHandler.getCraftingGridSlots(screen, output);
        if (range != null) ResidualCrafting.craft(screen.getMenu(), minecraft, range.getFirst(), range.getLast(), 1);
    }

    private static boolean keysDown(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        return (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
                && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS)
                && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
    }
}
