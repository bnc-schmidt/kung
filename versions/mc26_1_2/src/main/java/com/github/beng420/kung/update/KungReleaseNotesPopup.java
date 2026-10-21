package com.github.beng420.kung.update;

import com.github.beng420.kung.ui.UiBounds;
import com.github.beng420.kung.ui.UiScrollList;
import com.github.beng420.kung.ui.UiTheme;
import com.github.beng420.kung.ui.UiShapes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Modal content rendered over Kung settings, sharing the menu's GUI coordinates. */
public final class KungReleaseNotesPopup {
    private static final UiTheme THEME = UiTheme.menu();
    private static final int LINE_HEIGHT = 12;
    private static final int RELEASE_ROW_HEIGHT = 22;
    private final KungReleaseNotes notes;
    private final UiScrollList scroll = new UiScrollList();
    private final UiScrollList historyScroll = new UiScrollList();
    private List<Line> lines = List.of();
    private KungReleaseNotes.Snapshot wrappedSnapshot;
    private KungReleaseNotes.Notes displayed;
    private int wrappedWidth = -1;
    private Layout layout;
    private boolean historyFocused;
    private int historyPage = 1;
    private KungReleaseHistory.Release selectedRelease;
    private KungReleaseHistory.PageSnapshot drawnPage;
    private final List<ReleaseHit> releaseHits = new ArrayList<>();

    public KungReleaseNotesPopup(KungReleaseNotes notes) {
        this.notes = notes;
        notes.history().requestPage(historyPage);
    }

    public void draw(GuiGraphicsExtractor graphics, Font font, int width, int height, int mouseX, int mouseY) {
        graphics.nextStratum();
        layout = Layout.at(width, height);
        var snapshot = selectedNotes();
        releaseHits.clear();
        graphics.fill(0, 0, width, height, 0xAA000000);
        var panel = layout.panel();
        UiShapes.shadow(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 5);
        UiShapes.rounded(graphics, panel.x(), panel.y(), panel.width(), panel.height(), 5, THEME.panel());
        var content = layout.content();
        text(graphics, font, "What's new in Kung v" + selectedVersion(), content.x(), panel.y() + 11,
            content.width(), THEME.text());
        text(graphics, font, "Patch notes from GitHub", content.x(), panel.y() + 25,
            content.width(), THEME.muted());
        drawHistory(graphics, font, mouseX, mouseY);
        wrap(font, snapshot, content.width() - 8);
        drawNotes(graphics, font);
        button(graphics, font, layout.github(), "GitHub", mouseX, mouseY, false);
        button(graphics, font, layout.close(), snapshot.status() == KungReleaseNotes.Status.READY ? "Got it" : "Close",
            mouseX, mouseY, true);
        if (retryable()) button(graphics, font, layout.retry(), "Retry", mouseX, mouseY, false);
        if (snapshot.status() == KungReleaseNotes.Status.READY && displayed != snapshot.notes()) {
            displayed = snapshot.notes();
            if (selectedRelease == null) notes.acknowledge(displayed);
        }
    }

    private void drawNotes(GuiGraphicsExtractor graphics, Font font) {
        var content = layout.content();
        scroll.setOffset(scroll.offset(), lines.size() * LINE_HEIGHT, content.height());
        graphics.enableScissor(content.x(), content.y(), content.right(), content.bottom());
        int y = content.y() - scroll.offset();
        for (var line : lines) {
            if (y + LINE_HEIGHT > content.y() && y < content.bottom()) {
                graphics.text(font, line.text(), content.x(), y, line.heading() ? THEME.accent() : THEME.text(), false);
            }
            y += LINE_HEIGHT;
        }
        graphics.disableScissor();
        scrollbar(graphics, content, scroll, lines.size() * LINE_HEIGHT);
    }

    private void drawHistory(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        var sidebar = layout.sidebar();
        var content = layout.history();
        drawnPage = notes.history().page(historyPage);
        graphics.fill(sidebar.right() + 6, sidebar.y(), sidebar.right() + 7, sidebar.bottom(), THEME.controlHover());
        text(graphics, font, "Installed", sidebar.x(), sidebar.y(), sidebar.width(), THEME.muted());
        button(graphics, font, layout.current(), "v" + notes.version(), mouseX, mouseY, selectedRelease == null);
        text(graphics, font, "History", sidebar.x(), content.y() - 15, sidebar.width(), THEME.muted());
        if (historyPage > 1) button(graphics, font, layout.previous(), "<", mouseX, mouseY, false);
        if (drawnPage.page() != null && drawnPage.page().hasNext()) {
            button(graphics, font, layout.next(), ">", mouseX, mouseY, false);
        }
        if (drawnPage.status() != KungReleaseNotes.Status.FAILED
            && (historyPage > 1 || (drawnPage.page() != null && drawnPage.page().hasNext()))) {
            String page = Integer.toString(historyPage);
            text(graphics, font, page, sidebar.x() + (sidebar.width() - font.width(page)) / 2,
                layout.previous().y() + 5, sidebar.width() - 44, THEME.muted());
        }
        if (drawnPage.page() == null || drawnPage.page().releases().isEmpty()) {
            String message = switch (drawnPage.status()) {
                case FAILED -> "Could not load releases.";
                case READY -> "No releases.";
                default -> "Loading...";
            };
            graphics.enableScissor(content.x(), content.y(), content.right(), content.bottom());
            int y = content.y();
            for (var line : font.split(Component.literal(message), content.width())) {
                graphics.text(font, line, content.x(), y, THEME.muted(), false);
                y += LINE_HEIGHT;
            }
            graphics.disableScissor();
            if (drawnPage.status() == KungReleaseNotes.Status.FAILED) {
                button(graphics, font, layout.historyRetry(), "Retry", mouseX, mouseY, false);
            }
            return;
        }
        historyScroll.setOffset(historyScroll.offset(), historyHeight(), content.height());
        graphics.enableScissor(content.x(), content.y(), content.right(), content.bottom());
        int y = content.y() - historyScroll.offset();
        for (var release : drawnPage.page().releases()) {
            int top = Math.max(y, content.y());
            int bottom = Math.min(y + RELEASE_ROW_HEIGHT - 3, content.bottom());
            if (bottom > top) {
                var hit = new UiBounds(content.x(), top, content.width() - 6, bottom - top);
                boolean selected = selectedRelease == null ? release.version().equals(notes.version())
                    : selectedRelease.tag().equals(release.tag());
                button(graphics, font, new UiBounds(content.x(), y, hit.width(), RELEASE_ROW_HEIGHT - 3),
                    release.tag(), mouseX, mouseY, selected);
                releaseHits.add(new ReleaseHit(hit, release));
            }
            y += RELEASE_ROW_HEIGHT;
        }
        graphics.disableScissor();
        scrollbar(graphics, content, historyScroll, historyHeight());
    }

    /** All mouse input belongs to the modal, including its backdrop. Returns true only to dismiss it. */
    public boolean click(Screen parent, int mouseX, int mouseY, int button) {
        if (layout == null || button != 0) return false;
        if (layout.close().contains(mouseX, mouseY)) return dismiss();
        historyFocused = layout.sidebar().contains(mouseX, mouseY);
        if (layout.current().contains(mouseX, mouseY)) {
            select(null);
            return false;
        }
        if (layout.previous().contains(mouseX, mouseY) && historyPage > 1) {
            changePage(historyPage - 1);
            return false;
        }
        if (layout.next().contains(mouseX, mouseY) && drawnPage != null
            && drawnPage.page() != null && drawnPage.page().hasNext()) {
            changePage(historyPage + 1);
            return false;
        }
        if (layout.historyRetry().contains(mouseX, mouseY) && drawnPage != null
            && drawnPage.status() == KungReleaseNotes.Status.FAILED) {
            notes.history().requestPage(historyPage);
            return false;
        }
        for (var hit : releaseHits) {
            if (hit.bounds().contains(mouseX, mouseY)) {
                select(hit.release());
                return false;
            }
        }
        if (layout.github().contains(mouseX, mouseY)) {
            var page = displayed != null ? displayed.page()
                : selectedRelease == null ? notes.releasePage() : selectedRelease.page();
            Minecraft.getInstance().schedule(() -> ConfirmLinkScreen.confirmLinkNow(parent, page));
        } else if (layout.retry().contains(mouseX, mouseY) && retryable()) {
            if (selectedRelease == null) notes.retry();
            else notes.history().requestNotes(selectedRelease);
        }
        return false;
    }

    private void changePage(int page) {
        historyPage = page;
        historyScroll.reset();
        releaseHits.clear();
        drawnPage = null;
        notes.history().requestPage(page);
    }

    void select(KungReleaseHistory.Release release) {
        selectedRelease = release != null && !release.version().equals(notes.version()) ? release : null;
        displayed = null;
        scroll.reset();
        wrappedSnapshot = null;
        if (selectedRelease == null) notes.openMenu(true);
        else notes.history().requestNotes(selectedRelease);
    }

    String selectedVersion() { return selectedRelease == null ? notes.version() : selectedRelease.version(); }

    KungReleaseNotes.Snapshot selectedNotes() {
        return selectedRelease == null ? notes.snapshot() : notes.history().notes(selectedRelease.tag());
    }

    private int historyHeight() {
        var page = drawnPage == null ? null : drawnPage.page();
        return page == null ? 0 : page.releases().size() * RELEASE_ROW_HEIGHT;
    }

    public void scroll(int mouseX, int mouseY, double amount) {
        if (layout == null) return;
        if (layout.sidebar().contains(mouseX, mouseY)) historyFocused = true;
        else if (layout.content().contains(mouseX, mouseY)) historyFocused = false;
        else return;
        scrollFocused((int) Math.round(-amount * 36));
    }

    private void scrollFocused(int delta) {
        if (historyFocused) {
            historyScroll.scrollBy(delta, historyHeight(), layout.history().height());
            releaseHits.clear();
        } else {
            scroll.scrollBy(delta, lines.size() * LINE_HEIGHT, layout.content().height());
        }
    }

    public boolean key(int key) {
        if (key == 256 || key == 257 || key == 335) return dismiss();
        if (layout == null) return false;
        var focusedScroll = historyFocused ? historyScroll : scroll;
        int page = historyFocused ? layout.history().height() : layout.content().height();
        int contentHeight = historyFocused ? historyHeight() : lines.size() * LINE_HEIGHT;
        switch (key) {
            case 264 -> scrollFocused(36);
            case 265 -> scrollFocused(-36);
            case 266 -> focusedScroll.scrollBy(-page, contentHeight, page);
            case 267 -> focusedScroll.scrollBy(page, contentHeight, page);
            case 268 -> focusedScroll.reset();
            case 269 -> focusedScroll.setOffset(Integer.MAX_VALUE, contentHeight, page);
            default -> { }
        }
        releaseHits.clear();
        return false;
    }

    private void wrap(Font font, KungReleaseNotes.Snapshot snapshot, int width) {
        width = Math.max(1, width);
        if (snapshot == wrappedSnapshot && width == wrappedWidth) return;
        if (snapshot != wrappedSnapshot) scroll.reset();
        wrappedSnapshot = snapshot;
        wrappedWidth = width;
        String body = switch (snapshot.status()) {
            case READY -> snapshot.notes().body();
            case UNAVAILABLE -> "The GitHub release for this version has no published patch notes yet.\n\nYou can close this popup and try again later.";
            case FAILED -> "Could not load patch notes from GitHub.\n\nPlease try again later or open the release on GitHub.";
            default -> "Loading patch notes from GitHub...";
        };
        var result = new ArrayList<Line>();
        boolean fenced = false;
        for (String raw : body.replace("\r", "").split("\n", -1)) {
            String value = raw.stripTrailing();
            if (value.stripLeading().startsWith("```")) { fenced = !fenced; continue; }
            // A GitHub alert ("> [!CAUTION]" plus "> text") shows as red bold text without its markers.
            boolean alert = !fenced && value.stripLeading().startsWith(">");
            if (alert) {
                value = value.replaceFirst("^\\s*>\\s?", "");
                if (value.matches("^\\[![A-Z]+]\\s*$")) continue;
            }
            boolean heading = !fenced && value.matches("^#{1,6}\\s+.*");
            if (heading) value = value.replaceFirst("^#{1,6}\\s+", "");
            if (!fenced) {
                value = value.replaceFirst("^(\\s*)[-*+] ", "$1• ")
                    .replaceAll("!\\[([^]]*)]\\([^)]+\\)", "[Image: $1]")
                    .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1 ($2)")
                    .replace("**", "").replace("`", "");
            }
            var component = Component.literal(value);
            if (heading) component.withStyle(ChatFormatting.BOLD);
            else if (alert) component.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            var wrapped = font.split(component, width);
            if (wrapped.isEmpty()) result.add(new Line(FormattedCharSequence.EMPTY, false));
            else for (var line : wrapped) result.add(new Line(line, heading));
        }
        lines = List.copyOf(result);
    }

    private boolean retryable() {
        var status = selectedNotes().status();
        return status == KungReleaseNotes.Status.FAILED || status == KungReleaseNotes.Status.UNAVAILABLE;
    }

    private boolean dismiss() {
        notes.dismiss();
        return true;
    }

    private static void button(GuiGraphicsExtractor graphics, Font font, UiBounds bounds, String label,
                               int mouseX, int mouseY, boolean primary) {
        UiShapes.rounded(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 3, bounds.contains(mouseX, mouseY)
            ? primary ? THEME.accent() : THEME.controlHover() : primary ? THEME.accentDark() : THEME.control());
        String visible = font.plainSubstrByWidth(label, Math.max(0, bounds.width() - 8));
        graphics.text(font, visible, bounds.x() + (bounds.width() - font.width(visible)) / 2,
            bounds.y() + 5, THEME.text(), false);
    }

    private static void scrollbar(GuiGraphicsExtractor graphics, UiBounds viewport, UiScrollList scroll, int contentHeight) {
        int maxScroll = UiScrollList.maxOffset(contentHeight, viewport.height());
        if (maxScroll <= 0 || viewport.height() <= 0) return;
        int thumb = Math.min(viewport.height(), Math.max(8, viewport.height() * viewport.height() / contentHeight));
        int thumbY = viewport.y() + (viewport.height() - thumb) * scroll.offset() / maxScroll;
        graphics.fill(viewport.right() - 3, viewport.y(), viewport.right(), viewport.bottom(), THEME.control());
        graphics.fill(viewport.right() - 3, thumbY, viewport.right(), thumbY + thumb, THEME.accent());
    }

    private static void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int width, int color) {
        graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, width)), x, y, color, false);
    }

    private record Line(FormattedCharSequence text, boolean heading) { }
    private record ReleaseHit(UiBounds bounds, KungReleaseHistory.Release release) { }

    record Layout(UiBounds panel, UiBounds sidebar, UiBounds current, UiBounds history, UiBounds historyRetry,
                  UiBounds content, UiBounds retry, UiBounds github, UiBounds close, UiBounds previous, UiBounds next) {
        static Layout at(int screenWidth, int screenHeight) {
            int width = Math.max(180, Math.min(560, screenWidth - 24));
            int height = Math.max(120, Math.min(320, screenHeight - 24));
            int x = (screenWidth - width) / 2;
            int y = (screenHeight - height) / 2;
            int sidebarWidth = Math.clamp(width / 5, 62, 100);
            int contentX = x + sidebarWidth + 26;
            int contentWidth = width - sidebarWidth - 38;
            int buttonWidth = Math.min(58, (contentWidth - 12) / 3);
            int buttonsY = y + height - 30;
            int closeX = x + width - 12 - buttonWidth;
            return new Layout(new UiBounds(x, y, width, height),
                new UiBounds(x + 12, y + 12, sidebarWidth, height - 24),
                new UiBounds(x + 12, y + 28, sidebarWidth, 19),
                new UiBounds(x + 12, y + 70, sidebarWidth, height - 110),
                new UiBounds(x + 38, buttonsY, sidebarWidth - 26, 18),
                new UiBounds(contentX, y + 46, contentWidth, height - 86),
                new UiBounds(contentX, buttonsY, buttonWidth, 18),
                new UiBounds(closeX - buttonWidth - 6, buttonsY, buttonWidth, 18),
                new UiBounds(closeX, buttonsY, buttonWidth, 18),
                new UiBounds(x + 12, buttonsY, 20, 18),
                new UiBounds(x + 12 + sidebarWidth - 20, buttonsY, 20, 18));
        }
    }
}
