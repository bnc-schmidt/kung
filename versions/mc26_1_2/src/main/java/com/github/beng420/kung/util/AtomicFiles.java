package com.github.beng420.kung.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Replaces a file in one step, so a crash never leaves half a document. */
public final class AtomicFiles {
    private AtomicFiles() { }

    /** Writes {@code text} to a sibling {@code <file>.tmp} and moves it over {@code file}. */
    public static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        replace(temporary, file);
    }

    /** Moves {@code temporary} over {@code target}, atomically where the file system supports it. */
    public static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
