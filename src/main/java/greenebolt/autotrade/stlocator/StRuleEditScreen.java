package greenebolt.autotrade.stlocator;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** 单条多环定位规则的编辑子界面：4 个字段（最小/最大/种子/数据包）。 */
public class StRuleEditScreen extends GuiBase {
    private static final int FIELD_WIDTH = 220;

    private final Screen parent;
    @Nullable private final StLocator.StRule original;
    private final int index;
    private final Consumer<StLocator.StRule> onConfirm;

    @Nullable private GuiTextFieldGeneric minField;
    @Nullable private GuiTextFieldGeneric maxField;
    @Nullable private GuiTextFieldGeneric seedField;
    @Nullable private GuiTextFieldGeneric packsField;
    private List<String> availablePacks = List.of();

    public StRuleEditScreen(Screen parent, @Nullable StLocator.StRule original, int index,
                            Consumer<StLocator.StRule> onConfirm) {
        this.setParent(parent);
        this.parent = parent;
        this.original = original;
        this.index = index;
        this.onConfirm = onConfirm;
        this.title = index >= 0 ? "编辑规则" : "添加规则";
        readAvailablePacks();
    }

    private void readAvailablePacks() {
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("satella").resolve("datapacks");
        if (!Files.isDirectory(dir)) {
            this.availablePacks = List.of();
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(f -> f.getFileName().toString().endsWith(".zip"))
                .forEach(f -> names.add(f.getFileName().toString()));
            this.availablePacks = names;
        } catch (IOException e) {
            this.availablePacks = List.of();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        int w = this.getScreenWidth();
        int x = w / 2 - FIELD_WIDTH / 2;
        int y = 30;

        this.minField = new GuiTextFieldGeneric(x, y, FIELD_WIDTH, 16, this.font);
        this.maxField = new GuiTextFieldGeneric(x, y + 40, FIELD_WIDTH, 16, this.font);
        this.seedField = new GuiTextFieldGeneric(x, y + 80, FIELD_WIDTH, 16, this.font);
        this.packsField = new GuiTextFieldGeneric(x, y + 120, FIELD_WIDTH, 16, this.font);
        if (this.original != null) {
            this.minField.setText(String.valueOf(this.original.min()));
            this.maxField.setText(String.valueOf(this.original.max()));
            this.seedField.setText(String.valueOf(this.original.seed()));
            this.packsField.setText(String.join("|", this.original.packs()));
        }
        this.minField.setFocusedWrapper(true);
        addTextField(this.minField, null);
        addTextField(this.maxField, null);
        addTextField(this.seedField, null);
        addTextField(this.packsField, null);

        int by = y + 152;
        addButton(new ButtonGeneric(x, by, 100, 18, "确定"), (b, mb) -> confirm());
        addButton(new ButtonGeneric(x + 120, by, 100, 18, "取消"), (b, mb) -> closeGui(true));
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
        super.render(drawContext, mouseX, mouseY, delta);
        GuiContext g = GuiContext.fromGuiGraphics(drawContext);
        int w = this.getScreenWidth();
        int x = w / 2 - FIELD_WIDTH / 2;
        int y = 30;
        drawStringWithShadow(g, this.title, 10, 8, 0xFFFFFFC0);
        drawStringWithShadow(g, "最小切比雪夫距离", x, y - 11, 0xFFE0E0E0);
        drawStringWithShadow(g, "最大切比雪夫距离", x, y + 29, 0xFFE0E0E0);
        drawStringWithShadow(g, "种子", x, y + 69, 0xFFE0E0E0);
        drawStringWithShadow(g, "数据包（zip 文件名，| 分隔，留空 = 全部）", x, y + 109, 0xFFE0E0E0);
        if (!this.availablePacks.isEmpty()) {
            drawStringWithShadow(g, "可用数据包：" + String.join("、", this.availablePacks),
                10, this.getScreenHeight() - 22, 0xFF808080);
        }
    }

    private void confirm() {
        assert this.minField != null && this.maxField != null && this.seedField != null && this.packsField != null;
        int min;
        int max;
        long seed;
        try {
            min = Integer.parseInt(this.minField.getText().trim());
        } catch (NumberFormatException e) {
            addMessage(Message.MessageType.ERROR, "最小距离必须是整数", new Object[0]);
            return;
        }
        try {
            max = Integer.parseInt(this.maxField.getText().trim());
        } catch (NumberFormatException e) {
            addMessage(Message.MessageType.ERROR, "最大距离必须是整数", new Object[0]);
            return;
        }
        try {
            seed = Long.parseLong(this.seedField.getText().trim());
        } catch (NumberFormatException e) {
            addMessage(Message.MessageType.ERROR, "种子必须是整数", new Object[0]);
            return;
        }
        if (min > max) {
            addMessage(Message.MessageType.ERROR, "最小距离不能大于最大距离", new Object[0]);
            return;
        }
        List<String> packs = new ArrayList<>();
        for (String pack : this.packsField.getText().split("\\|")) {
            String p = pack.trim();
            if (!p.isEmpty()) {
                packs.add(p);
            }
        }
        this.onConfirm.accept(new StLocator.StRule(min, max, seed, packs));
        MinecraftClient.getInstance().setScreen(this.parent);
    }
}
