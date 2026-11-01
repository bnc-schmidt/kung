package com.github.beng420.kung.feature.experiment;

/** What an experiment model reads of one menu slot: item id, display name and enchantment glint. */
public record SlotView(String item, String name, boolean glint) {
    public static final SlotView EMPTY = new SlotView("", "", false);
}
