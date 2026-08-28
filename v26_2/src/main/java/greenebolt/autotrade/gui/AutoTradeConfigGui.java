package greenebolt.autotrade.gui;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.AutoTradeConfigs;
import greenebolt.autotrade.stlocator.StRulesEditorScreen;
import net.minecraft.client.Minecraft;

import java.util.List;

public class AutoTradeConfigGui extends GuiConfigsBase {
    public AutoTradeConfigGui() {
        super(10, 50, AutoTrade.MOD_ID, null, "satella.title.configs");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(AutoTradeConfigs.Trade.OPTIONS);
    }

    @Override
    public void initGui() {
        super.initGui();
        // MaLiLib 配置列表本身不支持按钮型配置项，这里在标题与列表之间补一个编辑器入口
        int w = this.getScreenWidth();
        ButtonGeneric button = new ButtonGeneric(w / 2 - 110, 28, 220, 18, "打开多环定位规则编辑器");
        this.addButton(button, (b, mb) ->
            Minecraft.getInstance().setScreenAndShow(new StRulesEditorScreen(this)));
    }
}
