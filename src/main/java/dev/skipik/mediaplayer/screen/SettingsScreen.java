package dev.skipik.mediaplayer.screen;

import dev.skipik.mediaplayer.config.ModConfig;
import dev.skipik.mediaplayer.util.TextUtil;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Overlay settings. In-world the background stays clear so changes are previewed live on the HUD. */
public final class SettingsScreen extends Screen {
    private static final int[] PALETTE = {
        0xFFFFFF, 0xFFD54F, 0x4FC3F7, 0x81C784, 0xF06292, 0xBA68C8, 0xFF8A65, 0xB0BEC5
    };
    private static final int[] LINE_COUNTS = {1, 3, 5, 7};
    private static final int COL_W = 150;
    private static final int ROW = 24;

    private final Screen parent;
    private int panelTop;
    private int panelBottom;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("mediaplayer.settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig cfg = ModConfig.get();
        int x0 = this.width / 2 - COL_W - 4;
        int x1 = this.width / 2 + 4;
        int y = 34;
        panelTop = y - 6;

        addRenderableWidget(cycle(x0, y, () -> Component.translatable("mediaplayer.option.lyrics", PlayerScreen.onOff(cfg.lyricsEnabled)),
            () -> cfg.lyricsEnabled = !cfg.lyricsEnabled));
        addRenderableWidget(cycle(x1, y, () -> Component.translatable("mediaplayer.option.position", Component.translatable(cfg.position.translationKey())),
            () -> cfg.position = cfg.position.next()));
        y += ROW;
        addRenderableWidget(new ValueSlider(x0, y, COL_W, 20, 0.5, 2.5, 0.05, cfg.scale,
            v -> Component.translatable("mediaplayer.option.scale", String.format(Locale.ROOT, "%.2fx", v)),
            v -> cfg.scale = (float) v));
        addRenderableWidget(new ValueSlider(x1, y, COL_W, 20, 0, 100, 1, cfg.backgroundOpacity * 100.0,
            v -> Component.translatable("mediaplayer.option.opacity", (int) Math.round(v)),
            v -> cfg.backgroundOpacity = (float) (v / 100.0)));
        y += ROW;
        addRenderableWidget(cycle(x0, y, () -> Component.translatable("mediaplayer.option.lines", cfg.lineCount),
            () -> cfg.lineCount = nextOf(LINE_COUNTS, cfg.lineCount)));
        addRenderableWidget(new ValueSlider(x1, y, COL_W, 20, -3000, 3000, 50, cfg.lyricsOffsetMs,
            v -> Component.translatable("mediaplayer.option.offset", (int) Math.round(v)),
            v -> cfg.lyricsOffsetMs = (int) Math.round(v)));
        y += ROW;
        addRenderableWidget(cycle(x0, y, () -> Component.translatable("mediaplayer.option.text_color", TextUtil.hex(cfg.textColor)),
            () -> cfg.textColor = nextOf(PALETTE, cfg.textColor)));
        addRenderableWidget(cycle(x1, y, () -> Component.translatable("mediaplayer.option.highlight_color", TextUtil.hex(cfg.highlightColor)),
            () -> cfg.highlightColor = nextOf(PALETTE, cfg.highlightColor)));
        y += ROW;
        addRenderableWidget(cycle(x0, y, () -> Component.translatable("mediaplayer.option.karaoke", PlayerScreen.onOff(cfg.karaoke)),
            () -> cfg.karaoke = !cfg.karaoke));
        addRenderableWidget(cycle(x1, y, () -> Component.translatable("mediaplayer.option.hide_paused", PlayerScreen.onOff(cfg.hideWhenPaused)),
            () -> cfg.hideWhenPaused = !cfg.hideWhenPaused));
        y += ROW;
        addRenderableWidget(cycle(x0, y, () -> Component.translatable("mediaplayer.option.toast", PlayerScreen.onOff(cfg.nowPlayingToast)),
            () -> cfg.nowPlayingToast = !cfg.nowPlayingToast));
        y += ROW;
        panelBottom = y + 2;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private static Button cycle(int x, int y, Supplier<Component> label, Runnable action) {
        return Button.builder(label.get(), b -> {
            action.run();
            b.setMessage(label.get());
        }).bounds(x, y, COL_W, 20).build();
    }

    private static int nextOf(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (this.minecraft.level == null) {
            super.extractBackground(g, mouseX, mouseY, a);
            return;
        }
        // Keep the world + lyrics overlay visible for a live preview.
        g.fill(this.width / 2 - COL_W - 12, panelTop - 26, this.width / 2 + COL_W + 12, panelBottom + 14, 0xB0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(this.font, getTitle(), this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, Component.translatable("mediaplayer.settings.hint"), this.width / 2, panelBottom + 2, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ModConfig.saveAsync();
        this.minecraft.gui.setScreen(parent);
    }
}
