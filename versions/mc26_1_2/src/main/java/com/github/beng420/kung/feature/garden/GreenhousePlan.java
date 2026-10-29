package com.github.beng420.kung.feature.garden;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a displayed grid means, derived once per grid change instead of per frame: the footprints of its
 * target cells, how many of each crop to plant and which soils the grown mutations need. Deriving it from
 * the grid (not from a solver result) keeps it right after rotating, mirroring or pasting a link.
 */
final class GreenhousePlan {
    /** A mutation's footprint: its top-left cell and size. */
    record Block(String mutation, int row, int column, int width, int height) { }

    private final GreenhouseGrid grid;
    private final List<Block> blocks;
    private final int[] blockAt = new int[GreenhouseState.CELLS];
    private final List<Block> plants;
    private final int[] plantAt = new int[GreenhouseState.CELLS];
    private final Map<String, Integer> crops;
    private final List<String> soils;

    private GreenhousePlan(GreenhouseGrid grid, List<Block> blocks, int[] blockAt, List<Block> plants, int[] plantAt,
                           Map<String, Integer> crops, List<String> soils) {
        this.grid = grid;
        this.blocks = blocks;
        System.arraycopy(blockAt, 0, this.blockAt, 0, blockAt.length);
        this.plants = plants;
        System.arraycopy(plantAt, 0, this.plantAt, 0, plantAt.length);
        this.crops = crops;
        this.soils = soils;
    }

    GreenhouseGrid grid() { return grid; }

    /** Footprints in row-major order of their top-left cells. */
    List<Block> blocks() { return blocks; }

    /** Index into {@link #blocks()} of the footprint holding this target cell, or -1 for any other cell. */
    int blockAt(int row, int column) { return blockAt[row * GreenhouseGrid.SIZE + column]; }

    /**
     * Crop name to plants to plant, most first and then by name: one per cell, except a mutation planted as an
     * ingredient, which is one per footprint (a 3x3 Snoozling is nine cells but one plant).
     */
    Map<String, Integer> crops() { return crops; }

    /**
     * The plants of mutations planted as ingredients (a Snoozling fills 3x3 cells, a Noctilume 2x2), as footprints in
     * row-major order of their top-left cells; ingredient cells of such a crop that do not fill a footprint are not
     * listed. The planner draws each as one large icon.
     */
    List<Block> plants() { return plants; }

    /** Index into {@link #plants()} of the footprint holding this cell, or -1 for any other cell. */
    int plantAt(int row, int column) { return plantAt[row * GreenhouseGrid.SIZE + column]; }

    List<String> soils() { return soils; }

    static GreenhousePlan of(GreenhouseGrid grid) {
        int size = GreenhouseGrid.SIZE;
        int[] blockAt = new int[size * size];
        Arrays.fill(blockAt, -1);
        List<Block> blocks = new ArrayList<>();
        Map<String, GreenhouseMutations.Mutation> recipes = new HashMap<>();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell == null || cell.flag() != GreenhouseGrid.TARGET || blockAt[row * size + column] >= 0) continue;
                String name = cell.name();
                GreenhouseMutations.Mutation recipe = recipes.computeIfAbsent(name,
                    key -> GreenhouseMutations.find(key).orElse(null));
                int width = recipe == null ? 1 : recipe.width();
                int height = recipe == null ? 1 : recipe.height();
                // The first free target cell in row-major order is the top-left of its footprint. A footprint
                // that does not fit (a hand-edited link) falls back to the turned one, then to one cell.
                if (!fits(grid, blockAt, name, row, column, width, height)) {
                    int turned = width;
                    width = height;
                    height = turned;
                    if (!fits(grid, blockAt, name, row, column, width, height)) {
                        width = 1;
                        height = 1;
                    }
                }
                for (int r = row; r < row + height; r++) {
                    for (int c = column; c < column + width; c++) blockAt[r * size + c] = blocks.size();
                }
                blocks.add(new Block(name, row, column, width, height));
            }
        }

        int[] plantAt = new int[size * size];
        Arrays.fill(plantAt, -1);
        List<Block> plants = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell == null || cell.flag() == GreenhouseGrid.TARGET || plantAt[row * size + column] >= 0) continue;
                GreenhouseMutations.Mutation recipe = recipes.computeIfAbsent(cell.name(),
                    key -> GreenhouseMutations.find(key).orElse(null));
                if (recipe == null || recipe.width() != recipe.height() || recipe.width() < 2) continue;
                int side = recipe.width();
                if (!fitsPlant(grid, plantAt, cell.name(), row, column, side)) continue;
                for (int r = row; r < row + side; r++) {
                    for (int c = column; c < column + side; c++) plantAt[r * size + c] = plants.size();
                }
                plants.add(new Block(cell.name(), row, column, side, side));
            }
        }

        Map<String, Integer> counted = new HashMap<>();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && cell.flag() != GreenhouseGrid.TARGET) counted.merge(cell.name(), 1, Integer::sum);
            }
        }
        // A mutation planted as an ingredient (Snoozling 3x3, Noctilume 2x2) fills a whole footprint with one plant.
        counted.replaceAll((name, cells) -> {
            int side = GreenhouseMutations.find(name).filter(mutation -> mutation.width() == mutation.height())
                .map(GreenhouseMutations.Mutation::width).orElse(1);
            return (cells + side * side - 1) / (side * side);
        });
        Map<String, Integer> crops = new LinkedHashMap<>();
        counted.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(entry -> crops.put(entry.getKey(), entry.getValue()));

        Set<String> soils = new LinkedHashSet<>();
        for (Block block : blocks) {
            GreenhouseMutations.Mutation recipe = recipes.get(block.mutation());
            if (recipe != null) soils.addAll(recipe.soil());
        }
        return new GreenhousePlan(grid, List.copyOf(blocks), blockAt, List.copyOf(plants), plantAt,
            Collections.unmodifiableMap(crops), List.copyOf(soils));
    }

    /** Whether a full side x side square of unclaimed ingredient cells of this crop starts at (row, column). */
    private static boolean fitsPlant(GreenhouseGrid grid, int[] plantAt, String name, int row, int column, int side) {
        for (int r = row; r < row + side; r++) {
            for (int c = column; c < column + side; c++) {
                if (!GreenhouseGrid.inside(r, c) || plantAt[r * GreenhouseGrid.SIZE + c] >= 0) return false;
                GreenhouseGrid.Cell cell = grid.get(r, c);
                if (cell == null || cell.flag() == GreenhouseGrid.TARGET || !cell.name().equals(name)) return false;
            }
        }
        return true;
    }

    private static boolean fits(GreenhouseGrid grid, int[] blockAt, String name, int row, int column, int width,
                                int height) {
        for (int r = row; r < row + height; r++) {
            for (int c = column; c < column + width; c++) {
                if (!GreenhouseGrid.inside(r, c) || blockAt[r * GreenhouseGrid.SIZE + c] >= 0) return false;
                GreenhouseGrid.Cell cell = grid.get(r, c);
                if (cell == null || cell.flag() != GreenhouseGrid.TARGET || !cell.name().equals(name)) return false;
            }
        }
        return true;
    }
}
