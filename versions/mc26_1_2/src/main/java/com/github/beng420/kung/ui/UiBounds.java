package com.github.beng420.kung.ui;

public record UiBounds(int x, int y, int width, int height) {
    public UiBounds {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("UI bounds cannot have a negative size.");
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(int pointX, int pointY) {
        return inside(pointX, pointY, x, y, width, height);
    }

    /** Whether the point lies in the box; the right and bottom edges are outside. */
    public static boolean inside(int pointX, int pointY, int x, int y, int width, int height) {
        return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
    }
}
