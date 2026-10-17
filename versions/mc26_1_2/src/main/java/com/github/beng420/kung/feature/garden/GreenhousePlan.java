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
    private final Map<String, Integer> crops;
    private final List<String> soils;

    private GreenhousePlan(GreenhouseGrid grid, List<Block> blocks, int[] blockAt, Map<String, Integer> crops,
                           List<String> soils) {
        this.grid = grid;
        this.blocks = blocks;
        System.arraycopy(blockAt, 0, this.blockAt, 0, blockAt.length);
        this.crops = crops;
        this.soils = soils;
    }

    GreenhouseGrid grid() { return grid; }

    /** Footprints in row-major order of their top-left cells. */
    List<Block> blocks() { return blocks; }

    /** Index into {@link #blocks()} of the footprint holding this target cell, or -1 for any other cell. */
    int blockAt(int row, int column) { return blockAt[row * GreenhouseGrid.SIZE + column]; }

    /** Crop name to cells to plant, most cells first and then by name. */
    Map<String, Integer> crops() { return crops; }

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

        Map<String, Integer> counted = new HashMap<>();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                GreenhouseGrid.Cell cell = grid.get(row, column);
                if (cell != null && cell.flag() != GreenhouseGrid.TARGET) counted.merge(cell.name(), 1, Integer::sum);
            }
        }
        Map<String, Integer> crops = new LinkedHashMap<>();
        counted.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .forEach(entry -> crops.put(entry.getKey(), entry.getValue()));

        Set<String> soils = new LinkedHashSet<>();
        for (Block block : blocks) {
            GreenhouseMutations.Mutation recipe = recipes.get(block.mutation());
            if (recipe != null) soils.addAll(recipe.soil());
        }
        return new GreenhousePlan(grid, List.copyOf(blocks), blockAt, Collections.unmodifiableMap(crops),
            List.copyOf(soils));
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
