package com.github.beng420.kung.config;

import java.util.List;
import java.util.function.Supplier;

/** Rows for a list in the config: rebuilt only when the list changes, so a row keeps its identity (hover, click) while a value does. */
abstract class LiveRows<K> implements Supplier<List<SettingEntry>> {
    private K key;
    private List<SettingEntry> rows = List.of();

    /** A snapshot of what the rows show; two snapshots are equal while the rows may stay. */
    protected abstract K key();

    protected abstract List<SettingEntry> build(K key);

    @Override
    public final List<SettingEntry> get() {
        K current = key();
        if (!rows.isEmpty() && current.equals(key)) return rows;
        key = current;
        return rows = List.copyOf(build(current));
    }
}
