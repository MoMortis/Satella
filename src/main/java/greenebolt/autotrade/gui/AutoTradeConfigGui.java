package greenebolt.autotrade.gui;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import greenebolt.autotrade.AutoTrade;
import greenebolt.autotrade.AutoTradeConfigs;

import java.util.List;

public class AutoTradeConfigGui extends GuiConfigsBase {
    public AutoTradeConfigGui() {
        super(10, 50, AutoTrade.MOD_ID, null, "satella.title.configs");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(AutoTradeConfigs.Trade.OPTIONS);
    }
}
