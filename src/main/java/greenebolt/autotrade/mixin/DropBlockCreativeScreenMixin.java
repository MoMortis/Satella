package greenebolt.autotrade.mixin;

import greenebolt.autotrade.DropBlock;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 创造模式物品栏内丢弃（THROW 点击在创造界面走独立路径） */
@Mixin(CreativeInventoryScreen.class)
public class DropBlockCreativeScreenMixin {
	// 注意：本项目构建不生成 refmap，运行时只能匹配“纯 intermediary 方法名”（带 yarn 名或带描述符的
	// 写法都会因重映射失败而找不到目标）。method_2383 = 创造界面 onMouseClick(Slot,int,int,SlotActionType)，
	// 该类内无同名重载（2 参重载是 method_64239），纯名字无歧义。
	@Inject(method = "method_2383", at = @At("HEAD"), cancellable = true)
	private void satella$blockThrow(Slot slot, int slotId, int button, SlotActionType actionType, CallbackInfo ci) {
		if (actionType != SlotActionType.THROW || DropBlock.suppressInternal) {
			return;
		}
		ItemStack stack;
		if (slot != null && slot.hasStack()) {
			stack = slot.getStack();
		} else {
			// handler 字段在父类 HandledScreen 上，直接用 getter，避免 @Shadow 解析不到
			ScreenHandler handler = ((HandledScreen<?>) (Object) this).getScreenHandler();
			stack = handler.getCursorStack();
		}
		if (!stack.isEmpty() && DropBlock.isBlockedItem(stack.getItem())) {
			ci.cancel();
		}
	}
}
