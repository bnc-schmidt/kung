package com.github.beng420.kung.util;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.runtime.KungPaths;
import com.github.beng420.kung.util.AtomicFiles;
import com.github.beng420.kung.util.CatacombsAverageCalculator.DungeonClass;
import com.github.beng420.kung.util.CatacombsAverageCalculator.PlayerData;
import com.github.beng420.kung.util.CatacombsAverageCalculator.ProfileData;
import com.github.beng420.kung.util.HypixelSkyBlockProfileClient.ProfileResult;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The local player's own dungeon XP per SkyBlock profile, so CA50 still answers without a key or the Kung server.
 * API lookups refresh it; the XP lines of every run are added on top while the API lags behind.
 */
public final class CatacombsLocalStore {
    public static final CatacombsLocalStore INSTANCE = new CatacombsLocalStore(
        KungPaths.fileLayout().settingsDirectory().resolve("ca-cache.json"), CatacombsLocalStore::clientPlayer);
    /** Hypixel's API lags a few minutes; within this of a run, local XP may still be ahead of it. */
    static final long RECENT_RUN_MILLIS = 10 * 60_000L;
    private static final long SAVE_DELAY_SECONDS = 5L;
    private static final Gson JSON = new Gson();

    private final Path file;
    private final Supplier<LocalPlayer> localPlayer;
    /** Minecraft profile UUID -> SkyBlock profile id -> saved profile. */
    private Map<String, Map<String, Saved>> players;
    private String activeProfileId = "";
    private boolean saveQueued;

    record LocalPlayer(String uuid, String name) { }

    record Saved(ProfileData profile, long apiUpdatedMillis, long localUpdatedMillis) {
        long updatedMillis() {
            return Math.max(apiUpdatedMillis, localUpdatedMillis);
        }
    }

    private record Document(Map<String, Map<String, Saved>> players) { }

    CatacombsLocalStore(Path file, Supplier<LocalPlayer> localPlayer) {
        this.file = file;
        this.localPlayer = localPlayer;
    }

    /** From Hypixel's {@code Profile ID:} join line; XP is booked into nothing until one arrives. */
    synchronized void setActiveProfile(String profileId) {
        activeProfileId = key(profileId);
    }

    /** Adds run XP ({@code null} class = Catacombs) to the active profile, if it was ever loaded from the API. */
    synchronized boolean book(DungeonClass dungeonClass, double xp) {
        Map<String, Saved> profiles = profiles(localPlayer.get());
        Saved saved = profiles.get(activeProfileId);
        if (saved == null || xp <= 0.0) {
            return false;
        }
        ProfileData profile = saved.profile();
        EnumMap<DungeonClass, Double> classXp = new EnumMap<>(DungeonClass.class);
        classXp.putAll(profile.classXp());
        if (dungeonClass != null) {
            classXp.merge(dungeonClass, xp, Double::sum);
        }
        double cataXp = profile.cataXp() + (dungeonClass == null ? xp : 0.0);
        profiles.put(activeProfileId, new Saved(withXp(profile, cataXp, classXp), saved.apiUpdatedMillis(),
            System.currentTimeMillis()));
        queueSave();
        return true;
    }

    /** Merges a lookup of the local player into the store, or answers from the store when the lookup failed. */
    public synchronized ProfileResult apply(String requestedName, ProfileResult result) {
        if (!isLocal(requestedName)) {
            return result;
        }
        if (result.success()) {
            return result.withPlayer(merge(result.player(), System.currentTimeMillis()));
        }
        LocalPlayer me = localPlayer.get();
        List<Saved> saved = List.copyOf(profiles(me).values());
        if (saved.isEmpty()) {
            return result;
        }
        Saved best = Collections.max(saved, Comparator
            .comparing((Saved entry) -> key(entry.profile().id()).equals(activeProfileId))
            .thenComparingLong(Saved::updatedMillis)
            .thenComparing(entry -> entry.profile().selected()));
        List<ProfileData> profiles = saved.stream().map(Saved::profile).toList();
        return ProfileResult.fromLocal(new PlayerData(me.name(), profiles, saved.indexOf(best)), best.updatedMillis());
    }

    /**
     * XP only grows, but the API lags: right after a booked run the larger value wins, later the API is the truth,
     * which also corrects a local over-count. Perks, stats and names always come from the API.
     */
    synchronized PlayerData merge(PlayerData api, long now) {
        LocalPlayer me = localPlayer.get();
        if (me == null) {
            return api;
        }
        Map<String, Saved> previous = profiles(me);
        Map<String, Saved> next = new LinkedHashMap<>();
        List<ProfileData> merged = new ArrayList<>();
        int selected = api.selectedIndex();
        for (ProfileData profile : api.profiles()) {
            String id = key(profile.id());
            Saved old = previous.get(id);
            long localUpdated = old == null ? 0L : old.localUpdatedMillis();
            if (old != null && now - localUpdated < RECENT_RUN_MILLIS) {
                EnumMap<DungeonClass, Double> classXp = new EnumMap<>(DungeonClass.class);
                for (DungeonClass dungeonClass : DungeonClass.values()) {
                    classXp.put(dungeonClass, Math.max(profile.classXp(dungeonClass), old.profile().classXp(dungeonClass)));
                }
                profile = withXp(profile, Math.max(profile.cataXp(), old.profile().cataXp()), classXp);
            }
            if (!id.isEmpty() && id.equals(activeProfileId)) {
                selected = merged.size();
            }
            merged.add(profile);
            if (!id.isEmpty()) {
                next.put(id, new Saved(profile, now, localUpdated));
            }
        }
        players.put(me.uuid(), next);
        queueSave();
        return new PlayerData(api.name(), merged, selected);
    }

    boolean isLocal(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        LocalPlayer me = localPlayer.get();
        return me != null && me.name().equalsIgnoreCase(name.trim());
    }

    synchronized void save() {
        saveQueued = false;
        if (players == null) {
            return;
        }
        try {
            AtomicFiles.write(file, JSON.toJson(new Document(players)));
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Could not save Kung's CA data cache.", exception);
        }
    }

    private void queueSave() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        // ponytail: one delayed write per burst of XP lines; a crash inside the delay loses that run until the next API merge.
        CompletableFuture.runAsync(this::save, CompletableFuture.delayedExecutor(SAVE_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    private Map<String, Saved> profiles(LocalPlayer me) {
        load();
        return me == null ? new HashMap<>() : players.computeIfAbsent(me.uuid(), ignored -> new LinkedHashMap<>());
    }

    private void load() {
        if (players != null) {
            return;
        }
        players = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Document document = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (document != null && document.players() != null) {
                document.players().forEach((uuid, profiles) -> {
                    Map<String, Saved> valid = new LinkedHashMap<>(profiles);
                    valid.values().removeIf(saved -> saved == null || saved.profile() == null || saved.profile().stats() == null);
                    players.put(uuid, valid);
                });
            }
        } catch (IOException | RuntimeException exception) {
            KungMod.LOGGER.warn("Could not read Kung's CA data cache.", exception);
        }
    }

    private static ProfileData withXp(ProfileData profile, double cataXp, Map<DungeonClass, Double> classXp) {
        return new ProfileData(profile.id(), profile.cuteName(), profile.selected(), cataXp, classXp,
            profile.classPerks(), profile.stats());
    }

    private static String key(String profileId) {
        return profileId == null ? "" : profileId.trim().toLowerCase(Locale.ROOT);
    }

    private static LocalPlayer clientPlayer() {
        Minecraft client = Minecraft.getInstance();
        User user = client == null ? null : client.getUser();
        return user == null || user.getProfileId() == null ? null : new LocalPlayer(user.getProfileId().toString(), user.getName());
    }
}
