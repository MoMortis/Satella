package greenebolt.autotrade.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import greenebolt.autotrade.gui.AutoTradeConfigGui;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            AutoTradeConfigGui gui = new AutoTradeConfigGui();
            gui.setParent(parent);
            return gui;
        };
    }
}
