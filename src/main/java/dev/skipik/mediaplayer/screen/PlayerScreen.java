package dev.skipik.mediaplayer.screen;

import dev.skipik.mediaplayer.api.AudiusApi;
import dev.skipik.mediaplayer.api.Track;
import dev.skipik.mediaplayer.config.ModConfig;
import dev.skipik.mediaplayer.lyrics.Lyrics;
import dev.skipik.mediaplayer.player.CoverManager;
import dev.skipik.mediaplayer.player.PlayerController;
import dev.skipik.mediaplayer.util.Http;
import dev.skipik.mediaplayer.util.TextUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class PlayerScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int COVER = 48;

    // Survive closing/reopening the screen.
    private static String lastQuery = "";
    private static List<Track> results = List.of();
    private static int page;
    private static String status;
    private static int requestId;

    private EditBox searchBox;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Button> addButtons = new ArrayList<>();
    private Button prevPage;
    private Button nextPage;
    private Button playPause;
    private Button shuffle;
    private Button repeat;
    private Button lyricsToggle;
    private int rows;
    private int left;
    private int top;
    private int panelW;
    private int leftW;
    private int rightX;
    private int rightW;
    private int listBottom;
    private int controlsY;

    private Boolean shownPaused;
    private Boolean shownShuffle;
    private ModConfig.Repeat shownRepeat;
    private Boolean shownLyrics;
    private String titleFor;
    private String titleText = "";
    private String artistText = "";
    private long timeSecond = -1;
    private String timeText = "";

    public PlayerScreen() {
        super(Component.translatable("mediaplayer.title"));
    }

    @Override
    protected void init() {
        panelW = Math.min(this.width - 16, 540);
        left = (this.width - panelW) / 2;
        top = 30;
        leftW = (int) (panelW * 0.56f);
        rightX = left + leftW + 10;
        rightW = panelW - leftW - 10;

        searchBox = new EditBox(this.font, left, top, leftW - 104, 20, Component.translatable("mediaplayer.search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.translatable("mediaplayer.search.hint"));
        searchBox.setValue(lastQuery);
        searchBox.setResponder(v -> lastQuery = v);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("mediaplayer.search.button"), b -> doSearch())
            .bounds(left + leftW - 102, top, 50, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("mediaplayer.trending"), b -> runQuery(AudiusApi.trending()))
            .bounds(left + leftW - 50, top, 50, 20).build());

        int rowY = top + 26;
        rows = Math.max(3, Math.min(9, (this.height - rowY - 40) / ROW_H));
        rowButtons.clear();
        addButtons.clear();
        for (int i = 0; i < rows; i++) {
            final int row = i;
            Button play = Button.builder(Component.empty(), b -> onRow(row, false))
                .bounds(left, rowY + i * ROW_H, leftW - 24, 20).build();
            Button add = Button.builder(Component.literal("+"), b -> onRow(row, true))
                .bounds(left + leftW - 22, rowY + i * ROW_H, 22, 20).build();
            add.setTooltip(Tooltip.create(Component.translatable("mediaplayer.queue.add")));
            rowButtons.add(addRenderableWidget(play));
            addButtons.add(addRenderableWidget(add));
        }
        listBottom = rowY + rows * ROW_H;
        prevPage = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            refreshRows();
        }).bounds(left, listBottom + 2, 20, 20).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            refreshRows();
        }).bounds(left + leftW - 20, listBottom + 2, 20, 20).build());

        PlayerController pc = PlayerController.get();
        ModConfig cfg = ModConfig.get();
        controlsY = top + COVER + 26;
        int bw = (rightW - 8) / 3;
        int hw = (rightW - 4) / 2;
        int y = controlsY;
        addRenderableWidget(Button.builder(Component.literal("|<"), b -> pc.previous()).bounds(rightX, y, bw, 20).build());
        playPause = addRenderableWidget(Button.builder(Component.empty(), b -> pc.togglePause())
            .bounds(rightX + bw + 4, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">|"), b -> pc.next())
            .bounds(rightX + 2 * (bw + 4), y, rightW - 2 * (bw + 4), 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("-10s"), b -> pc.seekRelative(-10_000L))
            .bounds(rightX, y, hw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10s"), b -> pc.seekRelative(10_000L))
            .bounds(rightX + hw + 4, y, rightW - hw - 4, 20).build());
        y += 24;
        shuffle = addRenderableWidget(Button.builder(Component.empty(), b -> cfg.shuffle = !cfg.shuffle)
            .bounds(rightX, y, hw, 20).build());
        repeat = addRenderableWidget(Button.builder(Component.empty(), b -> cfg.repeat = cfg.repeat.next())
            .bounds(rightX + hw + 4, y, rightW - hw - 4, 20).build());
        y += 24;
        addRenderableWidget(new ValueSlider(rightX, y, rightW, 20, 0, 100, 1, cfg.volume * 100.0,
            v -> Component.translatable("mediaplayer.volume", (int) Math.round(v)),
            v -> pc.setVolume((float) (v / 100.0))));
        y += 24;
        lyricsToggle = addRenderableWidget(Button.builder(Component.empty(), b -> cfg.lyricsEnabled = !cfg.lyricsEnabled)
            .bounds(rightX, y, hw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("mediaplayer.settings.button"),
            b -> this.minecraft.gui.setScreen(new SettingsScreen(this)))
            .bounds(rightX + hw + 4, y, rightW - hw - 4, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("mediaplayer.queue.clear"), b -> pc.clearQueue())
            .bounds(rightX + rightW - 60, this.height - 26, 60, 20).build());

        shownPaused = null;
        shownShuffle = null;
        shownRepeat = null;
        shownLyrics = null;
        titleFor = null;
        updateLabels();
        refreshRows();
        setInitialFocus(searchBox);
    }

    private void doSearch() {
        String q = searchBox.getValue().trim();
        if (!q.isEmpty()) runQuery(AudiusApi.search(q));
    }

    private void runQuery(CompletableFuture<List<Track>> future) {
        final int id = ++requestId;
        status = Component.translatable("mediaplayer.status.searching").getString();
        future.whenComplete((list, err) -> this.minecraft.execute(() -> {
            if (id != requestId) return;
            if (err != null) {
                results = List.of();
                status = Component.translatable("mediaplayer.status.error", Http.describe(err)).getString();
            } else {
                results = list;
                status = list.isEmpty() ? Component.translatable("mediaplayer.status.no_results").getString() : null;
            }
            page = 0;
            if (this.minecraft.gui.screen() == this) refreshRows();
        }));
    }

    private void onRow(int row, boolean addOnly) {
        int idx = page * rows + row;
        List<Track> res = results;
        if (idx < 0 || idx >= res.size()) return;
        PlayerController pc = PlayerController.get();
        if (addOnly) pc.enqueue(res.get(idx));
        else pc.playList(res, idx);
    }

    private void refreshRows() {
        List<Track> res = results;
        int pages = Math.max(1, (res.size() + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < rows; i++) {
            int idx = page * rows + i;
            Button play = rowButtons.get(i);
            Button add = addButtons.get(i);
            boolean vis = idx < res.size();
            play.visible = vis;
            add.visible = vis;
            if (vis) {
                Track t = res.get(idx);
                String label = t.title() + " - " + t.artist() + "  " + TextUtil.time(t.durationMs());
                play.setMessage(Component.literal(TextUtil.fit(this.font, label, play.getWidth() - 10)));
            }
        }
        boolean paged = res.size() > rows;
        prevPage.visible = paged;
        nextPage.visible = paged;
        prevPage.active = page > 0;
        nextPage.active = page < pages - 1;
    }

    /** Button labels only change when the underlying state changes (no per-frame Components). */
    private void updateLabels() {
        PlayerController pc = PlayerController.get();
        ModConfig cfg = ModConfig.get();
        boolean paused = pc.current() == null || pc.isPaused();
        if (shownPaused == null || shownPaused != paused) {
            shownPaused = paused;
            playPause.setMessage(Component.translatable(paused ? "mediaplayer.play" : "mediaplayer.pause"));
        }
        if (shownShuffle == null || shownShuffle != cfg.shuffle) {
            shownShuffle = cfg.shuffle;
            shuffle.setMessage(Component.translatable("mediaplayer.shuffle", onOff(cfg.shuffle)));
        }
        if (shownRepeat != cfg.repeat) {
            shownRepeat = cfg.repeat;
            repeat.setMessage(Component.translatable("mediaplayer.repeat", Component.translatable(cfg.repeat.translationKey())));
        }
        if (shownLyrics == null || shownLyrics != cfg.lyricsEnabled) {
            shownLyrics = cfg.lyricsEnabled;
            lyricsToggle.setMessage(Component.translatable("mediaplayer.lyrics", onOff(cfg.lyricsEnabled)));
        }
    }

    static Component onOff(boolean on) {
        return Component.translatable(on ? "options.on" : "options.off");
    }

    @Override
    public void tick() {
        super.tick();
        updateLabels();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox != null && searchBox.isFocused()
            && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            doSearch();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        ModConfig.saveAsync();
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(left - 6, top - 24, left + panelW + 6, this.height - 2, 0xA0000000);
        g.fill(rightX - 5, top, rightX - 4, this.height - 8, 0x30FFFFFF);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.centeredText(this.font, getTitle(), this.width / 2, 10, 0xFFFFFFFF);

        if (results.size() > rows) {
            int pages = (results.size() + rows - 1) / rows;
            g.centeredText(this.font, Component.translatable("mediaplayer.page", page + 1, pages),
                left + leftW / 2, listBottom + 8, 0xFFAAAAAA);
        }
        if (status != null) {
            g.text(this.font, status, left, listBottom + 28, 0xFFFFD54F);
        }
        renderNowPlaying(g);
        renderQueue(g);
    }

    private void renderNowPlaying(GuiGraphicsExtractor g) {
        PlayerController pc = PlayerController.get();
        Track t = pc.current();
        int textX = rightX + COVER + 6;
        int textW = rightW - COVER - 6;
        if (t == null) {
            g.fill(rightX, top, rightX + COVER, top + COVER, 0x30FFFFFF);
            g.text(this.font, Component.translatable("mediaplayer.nothing"), textX, top + 4, 0xFFAAAAAA);
        } else {
            CoverManager.Cover cover = CoverManager.get(t);
            if (cover != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, cover.id(), rightX, top, 0f, 0f, COVER, COVER,
                    cover.size(), cover.size(), cover.size(), cover.size());
            } else {
                g.fill(rightX, top, rightX + COVER, top + COVER, 0x30FFFFFF);
                g.centeredText(this.font, "\u266A", rightX + COVER / 2, top + COVER / 2 - 4, 0xFFAAAAAA);
            }
            if (!t.id().equals(titleFor)) {
                titleFor = t.id();
                titleText = TextUtil.fit(this.font, t.title(), textW);
                artistText = TextUtil.fit(this.font, t.artist(), textW);
            }
            g.text(this.font, titleText, textX, top + 4, 0xFFFFFFFF);
            g.text(this.font, artistText, textX, top + 16, 0xFFAAAAAA);
            g.text(this.font, statusLine(pc), textX, top + 30, 0xFF8FA3B8);
        }

        long pos = pc.positionMs();
        long dur = pc.durationMs();
        int barY = top + COVER + 6;
        g.fill(rightX, barY, rightX + rightW, barY + 3, 0x40FFFFFF);
        if (dur > 0) {
            int filled = (int) (rightW * Math.min(1.0, pos / (double) dur));
            g.fill(rightX, barY, rightX + filled, barY + 3, 0xFF000000 | ModConfig.get().highlightColor);
        }
        long second = pos / 1000L;
        if (second != timeSecond) {
            timeSecond = second;
            timeText = TextUtil.time(pos) + " / " + TextUtil.time(dur);
        }
        g.text(this.font, timeText, rightX, barY + 6, 0xFFCCCCCC);
    }

    private Component statusLine(PlayerController pc) {
        if (pc.lastError() != null) return Component.translatable("mediaplayer.msg.error", pc.lastError());
        if (pc.isBuffering()) return Component.translatable("mediaplayer.status.buffering");
        if (pc.isLyricsLoading()) return Component.translatable("mediaplayer.lyrics.loading");
        Lyrics l = pc.lyrics();
        if (l.isEmpty()) return Component.translatable("mediaplayer.lyrics.none");
        return Component.translatable(l.synced() ? "mediaplayer.lyrics.synced" : "mediaplayer.lyrics.plain");
    }

    private void renderQueue(GuiGraphicsExtractor g) {
        PlayerController pc = PlayerController.get();
        List<Track> q = pc.queue();
        int y = controlsY + 4 * 24 + 26;
        if (y + 12 > this.height - 28) return;
        g.text(this.font, Component.translatable("mediaplayer.queue", q.size()), rightX, y, 0xFFFFFFFF);
        y += 12;
        int start = Math.max(0, pc.index());
        boolean playing = pc.current() != null;
        for (int i = start; i < q.size() && y + 10 <= this.height - 30; i++, y += 11) {
            boolean isCurrent = playing && i == pc.index();
            String prefix = isCurrent ? "\u25B6 " : (i + 1) + ". ";
            int color = isCurrent ? 0xFF000000 | ModConfig.get().highlightColor : 0xFFBBBBBB;
            g.text(this.font, TextUtil.fit(this.font, prefix + q.get(i).title(), rightW), rightX, y, color);
        }
    }
}
