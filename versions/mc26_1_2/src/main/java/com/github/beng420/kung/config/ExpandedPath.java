package com.github.beng420.kung.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Which settings of the menu are open, from the top down: [0] a row of the feature, [1] a child of it, [2] a child of
 * that. Opening a row closes what was open beside it; closing a row closes everything below it.
 */
final class ExpandedPath<T> {
    private final List<T> path = new ArrayList<>();

    boolean isExpanded(T setting, int depth) {
        return path.size() > depth && path.get(depth) == setting;
    }

    void toggle(T setting, int depth) {
        boolean wasOpen = isExpanded(setting, depth);
        while (path.size() > depth) path.removeLast();
        if (!wasOpen) path.add(setting);
    }
}
