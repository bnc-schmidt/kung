package com.github.beng420.kung.config;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ExpandedPathTest {
    private final Object mayors = new Object();
    private final Object events = new Object();
    private final Object jerry = new Object();
    private final Object scorp = new Object();
    private final Object yotp = new Object();

    @Test public void nothingIsOpenAtFirst() {
        var path = new ExpandedPath<Object>();
        assertFalse(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(jerry, 1));
    }

    @Test public void aGroupOpensAndItsCommandOpensInsideIt() {
        var path = new ExpandedPath<Object>();
        path.toggle(mayors, 0);
        path.toggle(jerry, 1);
        assertTrue(path.isExpanded(mayors, 0));
        assertTrue(path.isExpanded(jerry, 1));
        // The same row on another level is not the open one.
        assertFalse(path.isExpanded(jerry, 0));
        assertFalse(path.isExpanded(mayors, 1));
    }

    @Test public void openingARowClosesItsSiblingsAndWhatWasBelowThem() {
        var path = new ExpandedPath<Object>();
        path.toggle(mayors, 0);
        path.toggle(jerry, 1);
        path.toggle(scorp, 1);
        assertTrue(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(jerry, 1));
        assertTrue(path.isExpanded(scorp, 1));
        // Another group at the top closes the first one with everything inside it.
        path.toggle(events, 0);
        assertFalse(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(scorp, 1));
        assertTrue(path.isExpanded(events, 0));
        path.toggle(yotp, 1);
        assertTrue(path.isExpanded(yotp, 1));
    }

    @Test public void closingARowClosesEverythingBelowIt() {
        var path = new ExpandedPath<Object>();
        path.toggle(mayors, 0);
        path.toggle(jerry, 1);
        path.toggle(mayors, 0);
        assertFalse(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(jerry, 1));
        // Opening the group again starts with its commands closed.
        path.toggle(mayors, 0);
        assertTrue(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(jerry, 1));
        // Closing the inner row leaves the group open.
        path.toggle(jerry, 1);
        path.toggle(jerry, 1);
        assertTrue(path.isExpanded(mayors, 0));
        assertFalse(path.isExpanded(jerry, 1));
    }
}
