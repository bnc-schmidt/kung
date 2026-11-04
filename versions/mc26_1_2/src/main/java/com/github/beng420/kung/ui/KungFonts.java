package com.github.beng420.kung.ui;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.KungConfig;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.github.beng420.kung.util.KungErrors;
import com.github.beng420.kung.util.KungHttp;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

/**
 * The menu font, built while the game runs. Any .ttf in the font folder can be picked, and the glyphs
 * are rasterized at the menu's real device resolution: one texel per pixel keeps every stem the same
 * width, which a font baked for one fixed scale cannot do.
 */
public final class KungFonts {
    public static final String VANILLA = "Minecraft";
    public static final String BUNDLED = "Inter";
    /** Families worth a download button; every other one still works as a dropped-in file. */
    public static final List<String> LIBRARY = List.of("Roboto", "Open Sans", "Lato", "Montserrat",
        "Poppins", "Source Sans 3", "Noto Sans", "Nunito", "Work Sans", "Rubik", "Karla", "Manrope",
        "IBM Plex Sans", "Fira Sans", "Oswald", "Bebas Neue", "JetBrains Mono", "Fira Code",
        "IBM Plex Mono", "Roboto Mono", "Space Mono", "Press Start 2P", "VT323", "Silkscreen");
    /** Glyph height in GUI units; the atlas holds SIZE * oversample pixels of it. */
    private static final float SIZE = 12F;
    private static final String BUNDLED_RESOURCE = "/assets/kung/font/inter.ttf";
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(KungMod.MOD_ID, "runtime_font");
    private static final Pattern TTF_URL = Pattern.compile("https://fonts\\.gstatic\\.com/[^)\\s\"]+\\.ttf");
    private static final long RESCAN_MILLIS = 2000L;
    private static final int MAX_FONT_BYTES = 8 * 1024 * 1024;

    private static volatile List<String> available = List.of(BUNDLED, VANILLA);
    private static long lastScanMillis;
    private static FontSet set;
    private static GlyphProvider provider;
    private static IntSet supported = IntSets.EMPTY_SET;
    private static String builtName = "";
    private static float builtOversample;
    private static GlyphSource composite;
    private static GlyphSource compositeFallback;
    private static volatile String status = "";
    private static int library;

    private KungFonts() {
    }

    /** The bundled font and the game's own, plus whatever .ttf files the folder holds. */
    public static List<String> available() {
        // The menu asks while drawing, so the list is kept ready instead of built per frame.
        if (System.currentTimeMillis() - lastScanMillis > RESCAN_MILLIS) refresh();
        return available;
    }

    public static Path folder() {
        return KungPaths.fileLayout().fontsDirectory();
    }

    public static void openFolder() {
        try {
            Files.createDirectories(folder());
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Could not create the font folder.", exception);
        }
        refresh();
        McCompat.openPath(folder());
    }

    public static String libraryChoice() {
        return LIBRARY.get(Math.clamp(library, 0, LIBRARY.size() - 1));
    }

    public static void selectLibraryChoice(String family) {
        library = Math.max(0, LIBRARY.indexOf(family));
    }

    public static String status() {
        return status.isBlank() ? "Drop .ttf files in the folder, or download one" : status;
    }

    /** Downloads the chosen family from Google Fonts into the folder and switches to it. */
    public static void download() {
        String family = libraryChoice();
        status = "Downloading " + family + "…";
        KungDebugRecorder.event("menu-font", "download started family=" + family);
        CompletableFuture.runAsync(() -> {
            try {
                byte[] font = fetch(family);
                Files.createDirectories(folder());
                Path target = folder().resolve(family + ".ttf");
                Files.write(target, font);
                refresh();
                KungConfig.get().misc.setMenuFont(target.getFileName().toString());
                status = family + " installed";
                KungDebugRecorder.event("menu-font", "download finished family=" + family
                    + " bytes=" + font.length + " file=" + target.getFileName());
            } catch (Exception exception) {
                KungMod.LOGGER.warn("Could not download the font {}.", family, exception);
                status = family + " failed: " + KungErrors.brief(exception);
                KungDebugRecorder.event("menu-font", "download failed family=" + family
                    + " reason=" + KungErrors.brief(exception));
            }
        });
    }

    /** Glyphs of the configured font, falling back to the menu's own chain for anything it lacks. */
    public static GlyphSource glyphs(GlyphSource fallback) {
        String wanted = KungConfig.get().misc.menuFont();
        // No client means no texture manager and no FreeType: model tests measure with the plain font.
        if (VANILLA.equals(wanted) || Minecraft.getInstance() == null) return fallback;
        float oversample = Math.clamp(UiScale.deviceScale(), 1F, 8F);
        if (!wanted.equals(builtName) || oversample != builtOversample) build(wanted, oversample);
        if (set == null) return fallback;
        if (composite == null || compositeFallback != fallback) {
            compositeFallback = fallback;
            composite = compose(set.source(false), fallback);
        }
        return composite;
    }

    private static GlyphSource compose(GlyphSource runtime, GlyphSource fallback) {
        IntSet has = supported;
        return new GlyphSource() {
            @Override public BakedGlyph getGlyph(int codepoint) {
                return has.contains(codepoint) ? runtime.getGlyph(codepoint) : fallback.getGlyph(codepoint);
            }

            @Override public BakedGlyph getRandomGlyph(RandomSource random, int width) {
                // Obfuscated text needs a glyph of an exact width, which only the full chain can promise.
                return fallback.getRandomGlyph(random, width);
            }
        };
    }

    private static void build(String name, float oversample) {
        release();
        // Kept even when loading fails, so a broken font is not retried on every frame.
        builtName = name;
        builtOversample = oversample;
        ByteBuffer memory = null;
        try (InputStream stream = open(name)) {
            memory = TextureUtil.readResource(stream);
            provider = bold(new TrueTypeGlyphProvider(memory, face(memory), SIZE, oversample, 0F, 0F, ""),
                oversample);
            supported = provider.getSupportedGlyphs();
            set = new FontSet(new GlyphStitcher(Minecraft.getInstance().getTextureManager(), ATLAS));
            set.reload(List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
            KungDebugRecorder.event("menu-font", "loaded name=" + name + " oversample=" + oversample
                + " glyphs=" + supported.size());
        } catch (Exception exception) {
            KungMod.LOGGER.warn("Could not load the menu font {}.", name, exception);
            KungDebugRecorder.event("menu-font", "load failed name=" + name
                + " reason=" + KungErrors.brief(exception));
            if (provider == null && memory != null) MemoryUtil.memFree(memory);
            release();
        }
    }

    /**
     * Bold draws the glyph a second time, shifted. Minecraft's own unit is a whole pixel of its bitmap
     * font, which is wider than a stem of a scaled TrueType glyph, so the letter tears into two lines.
     * One device pixel always thickens, and half a stem is as far as it may go at any resolution.
     */
    private static GlyphProvider bold(GlyphProvider base, float oversample) {
        float offset = Math.max(1F / oversample, SIZE / 24F);
        return new GlyphProvider() {
            @Override public UnbakedGlyph getGlyph(int codepoint) {
                UnbakedGlyph glyph = base.getGlyph(codepoint);
                return glyph == null ? null : new UnbakedGlyph() {
                    @Override public GlyphInfo info() {
                        GlyphInfo info = glyph.info();
                        return new GlyphInfo() {
                            @Override public float getAdvance() { return info.getAdvance(); }
                            @Override public float getBoldOffset() { return offset; }
                            @Override public float getShadowOffset() { return info.getShadowOffset(); }
                        };
                    }

                    @Override public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) { return glyph.bake(stitcher); }
                };
            }

            @Override public IntSet getSupportedGlyphs() { return base.getSupportedGlyphs(); }

            @Override public void close() { base.close(); }
        };
    }

    private static InputStream open(String name) throws IOException {
        if (BUNDLED.equals(name)) {
            InputStream stream = KungFonts.class.getResourceAsStream(BUNDLED_RESOURCE);
            if (stream == null) throw new IOException("Bundled font missing from the jar");
            return stream;
        }
        Path file = folder().resolve(Path.of(name).getFileName().toString());
        if (!Files.isRegularFile(file)) throw new IOException("No font file named " + name);
        return Files.newInputStream(file);
    }

    private static FT_Face face(ByteBuffer memory) throws IOException {
        synchronized (FreeTypeUtil.LIBRARY_LOCK) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                FreeTypeUtil.assertError(
                    FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), memory, 0L, pointer),
                    "Initializing font face");
                FT_Face face = FT_Face.create(pointer.get());
                String format = FreeType.FT_Get_Font_Format(face);
                if (!"TrueType".equals(format)) {
                    FreeType.FT_Done_Face(face);
                    throw new IOException("Only TrueType fonts work here, this one is " + format);
                }
                FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face, FreeType.FT_ENCODING_UNICODE),
                    "Find unicode charmap");
                return face;
            }
        }
    }

    private static void release() {
        if (set != null) set.close();
        if (provider != null) provider.close();
        set = null;
        provider = null;
        supported = IntSets.EMPTY_SET;
        composite = null;
        compositeFallback = null;
    }

    private static void refresh() {
        lastScanMillis = System.currentTimeMillis();
        var names = new ArrayList<>(List.of(BUNDLED, VANILLA));
        Path directory = folder();
        if (Files.isDirectory(directory)) {
            try (var entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".ttf"))
                    .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
                    .forEach(names::add);
            } catch (IOException exception) {
                KungMod.LOGGER.warn("Could not read the font folder.", exception);
            }
        }
        available = List.copyOf(names);
    }

    private static byte[] fetch(String family) throws IOException, InterruptedException {
        HttpClient client = KungHttp.download();
        String css = body(client, "https://fonts.googleapis.com/css2?family=" + family.replace(' ', '+'),
            HttpResponse.BodyHandlers.ofString());
        byte[] font = body(client, ttfUrl(css), HttpResponse.BodyHandlers.ofByteArray());
        if (font.length > MAX_FONT_BYTES) throw new IOException("font is larger than 8 MB");
        return font;
    }

    /** The stylesheet lists one source per subset; any of them is the same font file. */
    static String ttfUrl(String css) throws IOException {
        Matcher matcher = TTF_URL.matcher(css);
        if (!matcher.find()) throw new IOException("no TrueType file offered");
        return matcher.group();
    }

    private static <T> T body(HttpClient client, String url, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            // Google serves woff2 to browsers it knows and eot to ancient ones; an agent it cannot
            // place gets plain TrueType, which is the only format Minecraft reads.
            .header("User-Agent", "Kung-MenuFont")
            .GET()
            .build();
        HttpResponse<T> response = client.send(request, handler);
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }
}
