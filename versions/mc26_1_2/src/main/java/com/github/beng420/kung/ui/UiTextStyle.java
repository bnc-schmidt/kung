package com.github.beng420.kung.ui;

public record UiTextStyle(int color, boolean shadow) {
    public static UiTextStyle normal(UiTheme theme) {
        return new UiTextStyle(theme.text(), true);
    }

    public static UiTextStyle muted(UiTheme theme) {
        return new UiTextStyle(theme.muted(), true);
    }
}
