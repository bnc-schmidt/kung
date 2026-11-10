package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.util.AtomicFiles;
import com.github.beng420.kung.util.KungDebugRecorder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The player's Hypixel friends, for the name suggestions of {@code FriendNameCompletion}. The tracker only listens and
 * never sends a command. A friend who comes online or goes offline is added from Hypixel's "Friend >" line, and every
 * page of {@code /friend list} the player opens adds its names. Kept per account in {@code friends.json}, so a restart
 * still has them. A friend who was removed stays until the player has opened every page of the list in turn, which
 * then replaces it.
 */
public final class HypixelFriendTracker {
    public static final HypixelFriendTracker INSTANCE = new HypixelFriendTracker(
        KungPaths.fileLayout().settingsDirectory().resolve("friends.json"), HypixelFriendTracker::clientAccount);

    /** "<< Friends (Page 2 of 20) >>"; the arrows are missing on the first and the last page. */
    private static final Pattern PAGE_HEADER = Pattern.compile(
        "^(?:<< )?Friends \\(Page (?<page>\\d+) (?:of|/) (?<pages>\\d+)\\)(?: >>)?$", Pattern.CASE_INSENSITIVE);
    /** "Name is in SkyBlock - Garden", "Name is currently offline"; the rank is only a colour, no text comes before. */
    private static final Pattern LIST_ENTRY = Pattern.compile("^(?<name>[A-Za-z0-9_]{3,16})\\s+is\\s.*$");
    /** "Friend > Name joined." and "Friend > Name left." */
    private static final Pattern FRIEND_NOTICE = Pattern.compile(
        "^Friend\\s*>\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+(?:joined|left)\\.?$");
    private static final Pattern SEPARATOR = Pattern.compile("^[-\\u25AC]{10,}$");
    private static final Gson JSON = new Gson();
    private static final Type FILE_TYPE = new TypeToken<Map<String, List<String>>>() { }.getType();
    /** One page prints within this; a page with no closing line stops being read after it. */
    private static final long PAGE_WINDOW_MILLIS = 3_000L;

    private final Path file;
    private final Supplier<String> account;
    /** Every saved account's names; this account's entry is {@link #names}. Null until first used. */
    private Map<String, List<String>> saved;
    /** Lower case name -> name as Hypixel wrote it. */
    private Map<String, String> names;
    /** Pages 1 to the last one opened, null once a page was skipped: only a full read may replace the list. */
    private Map<String, String> collecting;
    private final Map<String, String> pageNames = new LinkedHashMap<>();
    private int page;
    private int pages;
    private int lastPage;
    private long pageUntilMillis;

    HypixelFriendTracker(Path file, Supplier<String> account) {
        this.file = file;
        this.account = account;
    }

    public static void initializeClient() {
        KungMessages.onGameText(message -> INSTANCE.observe(message, System.currentTimeMillis()));
    }

    /** The friends known, as Hypixel writes them. */
    public synchronized List<String> names() {
        load();
        return new ArrayList<>(names.values());
    }

    /** Reads one game message, which may hold several lines. */
    synchronized void observe(String message, long now) {
        if (message == null || message.isBlank()) return;
        load();
        for (String raw : HypixelLocation.lines(message)) {
            String line = HypixelLocation.clean(raw);
            Matcher header = PAGE_HEADER.matcher(line);
            if (header.matches()) {
                startPage(parse(header.group("page")), parse(header.group("pages")), now);
            } else if (SEPARATOR.matcher(line).matches()) {
                if (now < pageUntilMillis) endPage();
            } else if (now < pageUntilMillis) {
                Matcher entry = LIST_ENTRY.matcher(line);
                if (entry.matches()) pageNames.put(key(entry.group("name")), entry.group("name"));
            } else {
                Matcher notice = FRIEND_NOTICE.matcher(line);
                if (notice.matches() && names.putIfAbsent(key(notice.group("name")), notice.group("name")) == null) {
                    save();
                }
            }
        }
    }

    private void startPage(int number, int total, long now) {
        pageNames.clear();
        page = number;
        pages = total;
        pageUntilMillis = now + PAGE_WINDOW_MILLIS;
        if (number == 1) collecting = new LinkedHashMap<>();
        // A page opened twice (a double click) keeps the run.
        else if (collecting != null && number != lastPage && number != lastPage + 1) collecting = null;
        lastPage = number;
    }

    private void endPage() {
        pageUntilMillis = 0L;
        // A page that found no name is a format this tracker does not know, not a page without friends.
        if (pageNames.isEmpty()) return;
        names.putAll(pageNames);
        if (collecting != null) {
            collecting.putAll(pageNames);
            if (page >= pages) {
                names = collecting;
                collecting = null;
            }
        }
        save();
        KungDebugRecorder.event("friends", "read page " + page + " of " + pages + ", " + names.size() + " friends known");
    }

    private void load() {
        if (names != null) return;
        names = new LinkedHashMap<>();
        saved = new HashMap<>();
        if (!Files.isRegularFile(file)) return;
        try {
            Map<String, List<String>> document = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FILE_TYPE);
            if (document == null) return;
            saved.putAll(document);
            String key = account.get();
            for (String name : key == null ? List.<String>of() : document.getOrDefault(key, List.of())) {
                if (HypixelLocation.isPlayerName(name)) names.put(key(name), name);
            }
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Could not read Kung's friend list cache.", exception);
        }
    }

    private void save() {
        String key = account.get();
        if (key == null) return;
        saved.put(key, new ArrayList<>(names.values()));
        try {
            AtomicFiles.write(file, JSON.toJson(saved));
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Could not save Kung's friend list cache.", exception);
        }
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String clientAccount() {
        Minecraft client = Minecraft.getInstance();
        User user = client == null ? null : client.getUser();
        return user == null || user.getProfileId() == null ? null : user.getProfileId().toString();
    }
}
