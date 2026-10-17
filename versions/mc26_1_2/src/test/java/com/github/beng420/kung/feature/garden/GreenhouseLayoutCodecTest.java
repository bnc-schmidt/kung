package com.github.beng420.kung.feature.garden;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import org.junit.Test;

public final class GreenhouseLayoutCodecTest {
    private static String link(String json) {
        return GreenhouseLayoutCodec.URL + LZString.compressToEncodedURIComponent(json);
    }

    private static GreenhouseGrid decoded(String text) {
        Optional<GreenhouseGrid> grid = GreenhouseLayoutCodec.decode(text);
        assertTrue("expected a layout in: " + text, grid.isPresent());
        return grid.get();
    }

    @Test
    public void decodesTheSitesJsonShape() {
        GreenhouseGrid grid = decoded(link("[[0,0,\"Wheat Seeds\",1],[3,4,\"Carrot\",0],[9,9,\"Sunflower\",2]]"));

        assertEquals(new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.INGREDIENT), grid.get(0, 0));
        assertEquals(new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.TARGET), grid.get(3, 4));
        assertEquals(new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS), grid.get(9, 9));
        assertEquals(3, grid.filled());
    }

    @Test
    public void encodeEmitsRowColumnNameFlagAndTheUrlPrefix() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(1, 2, new GreenhouseGrid.Cell("Carrot", GreenhouseGrid.TARGET));
        grid.set(0, 5, new GreenhouseGrid.Cell("do not eat shroom", GreenhouseGrid.INGREDIENT));

        String url = GreenhouseLayoutCodec.encode(grid);

        assertTrue(url.startsWith("https://skymutations.eu/greenhouse?layout="));
        String json = LZString.decompressFromEncodedURIComponent(url.substring(GreenhouseLayoutCodec.URL.length()));
        // Row-major order, no spaces, the site's spelling of the crop.
        assertEquals("[[0,5,\"Do-not-eat-shroom\",1],[1,2,\"Carrot\",0]]", json);
    }

    @Test
    public void roundTripsFlagsAndUnknownNames() {
        GreenhouseGrid grid = new GreenhouseGrid();
        grid.set(0, 0, new GreenhouseGrid.Cell("Wheat Seeds", GreenhouseGrid.TARGET));
        grid.set(4, 7, new GreenhouseGrid.Cell("Brand New Crop ä & = \"quoted\"", GreenhouseGrid.INGREDIENT));
        grid.set(9, 0, new GreenhouseGrid.Cell("Sunflower", GreenhouseGrid.BONUS));
        grid.set(9, 9, new GreenhouseGrid.Cell("Mystery", 7));

        assertEquals(grid, decoded(GreenhouseLayoutCodec.encode(grid)));
        assertEquals(new GreenhouseGrid(), decoded(GreenhouseLayoutCodec.encode(new GreenhouseGrid())));
    }

    @Test
    public void roundTripsAFullGrid() {
        GreenhouseGrid grid = new GreenhouseGrid();
        for (int row = 0; row < GreenhouseGrid.SIZE; row++) {
            for (int column = 0; column < GreenhouseGrid.SIZE; column++) {
                String name = GreenhouseCrops.NAMES.get((row * 7 + column) % GreenhouseCrops.NAMES.size());
                grid.set(row, column, new GreenhouseGrid.Cell(name, (row + column) % 3));
            }
        }
        String url = GreenhouseLayoutCodec.encode(grid);

        assertTrue(url.length() < GreenhouseLayoutCodec.MAX_LINK_LENGTH);
        assertEquals(grid, decoded(url));
    }

    @Test
    public void acceptsUrlQueryFragmentAndBareForms() {
        String json = "[[2,3,\"Carrot\",0]]";
        GreenhouseGrid expected = decoded(link(json));
        String bare = LZString.compressToEncodedURIComponent(json);

        assertEquals(expected, decoded(bare));
        assertEquals(expected, decoded("?layout=" + bare));
        assertEquals(expected, decoded("layout=" + bare));
        assertEquals(expected, decoded("  " + link(json) + "\n"));
        assertEquals(expected, decoded("http://localhost/greenhouse?foo=1&layout=" + bare));
        assertEquals(expected, decoded(link(json) + "&utm_source=discord&x=1"));
        assertEquals(expected, decoded(link(json) + "#top"));
    }

    @Test
    public void plusSignsMaySurviveAsSpacesOrPercentEscapes() {
        String json = "[[0,0,\"Cactus\",1],[0,1,\"Cactus\",1],[0,2,\"Cactus\",1]]";
        String bare = LZString.compressToEncodedURIComponent(json);
        assertTrue(bare.contains("+"));
        GreenhouseGrid expected = decoded(bare);

        assertEquals(expected, decoded(GreenhouseLayoutCodec.URL + bare.replace('+', ' ')));
        assertEquals(expected, decoded(GreenhouseLayoutCodec.URL + bare.replace("+", "%2B").replace("$", "%24")));
        assertEquals(3, expected.filled());
    }

    @Test
    public void namesMatchKnownCropsLooselyButUnknownNamesStayAsTheyCame() {
        GreenhouseGrid grid = decoded(link("[[0,0,\"wheat  seeds\",1],[0,1,\"all in aloe\",1],[0,2,\"Totally New\",1]]"));

        assertEquals("Wheat Seeds", grid.get(0, 0).name());
        assertEquals("All-in Aloe", grid.get(0, 1).name());
        assertEquals("Totally New", grid.get(0, 2).name());
    }

    @Test
    public void missingFlagDefaultsToIngredientAndLaterEntriesWin() {
        GreenhouseGrid grid = decoded(link("[[0,0,\"Carrot\"],[0,0,\"Potato\",0]]"));

        assertEquals(new GreenhouseGrid.Cell("Potato", GreenhouseGrid.TARGET), grid.get(0, 0));
        assertEquals(GreenhouseGrid.INGREDIENT, decoded(link("[[1,1,\"Carrot\"]]")).get(1, 1).flag());
    }

    @Test
    public void namesWithoutALetterOrDigitOrTooLongAreRejected() {
        for (String name : new String[] {"-", "_", "--", " - ", "?!", "...", "\u2014", "-".repeat(30),
            "A".repeat(GreenhouseLayoutCodec.MAX_NAME_LENGTH + 1)}) {
            assertEquals("should be rejected: " + name, Optional.empty(),
                GreenhouseLayoutCodec.decode(link("[[0,0,\"" + name + "\",1]]")));
        }
        // One bad entry rejects the whole link, so no cell of it reaches the screen.
        assertEquals(Optional.empty(), GreenhouseLayoutCodec.decode(link("[[0,0,\"Carrot\",1],[0,1,\"-\",1]]")));
    }

    @Test
    public void ordinaryAndForeignLettersStillPassTheNameCheck() {
        String longest = "B".repeat(GreenhouseLayoutCodec.MAX_NAME_LENGTH);
        GreenhouseGrid grid = decoded(link("[[0,0,\"Carrot\",1],[0,1,\"\u00c4pfel\",1],[0,2,\"\u4f5c\u7269\",1],"
            + "[0,3,\"Mk-2\",1],[0,4,\"" + longest + "\",1],[0,5,\"7\",1]]"));

        assertEquals(6, grid.filled());
        assertEquals(longest, grid.get(0, 4).name());
    }

    @Test
    public void malformedInputIsAnEmptyResultInsteadOfAnException() {
        String[] bad = {
            null, "", "   ", "https://skymutations.eu/greenhouse", GreenhouseLayoutCodec.URL, "layout=",
            "hello world, this is not a layout", "%%%", "%zz", "https://example.com/?layout=!!!",
            link("not json"), link("{\"a\":1}"), link("[1,2,3]"), link("[[0,0]]"), link("[[10,0,\"Carrot\",1]]"),
            link("[[0,-1,\"Carrot\",1]]"), link("[[0.5,0,\"Carrot\",1]]"), link("[[\"0\",0,\"Carrot\",1]]"),
            link("[[0,0,5,1]]"), link("[[0,0,\"\",1]]"), link("[[0,0,\"Carrot\",\"x\"]]"),
            link("[[0,0,null,1]]"), link("[[0,0,[\"Carrot\"],1]]"), link("[[[[[[[[["),
            "a".repeat(GreenhouseLayoutCodec.MAX_LINK_LENGTH + 1),
        };
        for (String text : bad) {
            assertEquals("should be rejected: " + text, Optional.empty(), GreenhouseLayoutCodec.decode(text));
        }
    }
}
