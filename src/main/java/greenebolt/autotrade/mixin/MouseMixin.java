package greenebolt.autotrade.mixin;

import greenebolt.autotrade.AutoTrade;
import net.minecraft.client.Mouse;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void autoTrade$trackRightMouse(long window, MouseInput input, int action, CallbackInfo ci) {
        if (input.button() == InputUtil.GLFW_MOUSE_BUTTON_RIGHT) {
            AutoTrade.updatePhysicalUseKeyState(action != InputUtil.GLFW_RELEASE);
        }
    }
}
