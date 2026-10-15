package com.github.beng420.kung.feature.dungeon;

import com.github.beng420.kung.compat.McCompat;
import com.github.beng420.kung.config.category.DungeonConfig;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Tells a healer to wish, but only when wishing is actually possible. */
public final class WishAlert {
    /**
     * Hypixel's own line, not a mod's: it appears in local traces from 2026-09-11, six days before
     * NoammAddons was installed here, and NoammAddons only matches on it as well.
     */
    private static final Pattern ENRAGED = Pattern.compile("^⚠ (\\w+) is enraged! ⚠$");
    /** Sidebar teammate line, e.g. "[H] yrkuna 12,345❤"; a dead teammate shows DEAD and never matches. */
    private static final Pattern TEAMMATE = Pattern.compile("\\[[HMABT]\\] (\\w{1,16}) ([\\d,.]+)(k?) ?❤");

    private WishAlert() { }

    /** Healer with Wish ready; the developer bypass drops both gates, never the feature toggle. */
    static boolean canWish(DungeonConfig config, DungeonRunStats.DungeonClass selfClass,
        int experienceLevel, boolean developer) {
        if (config == null || !config.wishAlertEnabled()) return false;
        // Both gates drop only for the developer account AND the opt-in flag, so a copied config
        // cannot turn someone else's alert into a permanent siren.
        if (developer && config.devWishAlertAnyClassEnabled()) return true;
        // Hypixel drives the ultimate cooldown through the vanilla experience level, counting
        // it down to zero, so a non-zero level means Wish is still charging.
        // ponytail: assumes 0 is the idle value; if Hypixel ever idles non-zero this goes
        // quiet rather than crying wolf. Flip the comparison once a run confirms it.
        return selfClass == DungeonRunStats.DungeonClass.HEALER && experienceLevel == 0;
    }

    /** Returns the enraged boss when this message should alert, else null. */
    static String enragedBoss(String raw, DungeonConfig config,
        DungeonRunStats.DungeonClass selfClass, int experienceLevel, boolean developer) {
        if (raw == null || !canWish(config, selfClass, experienceLevel, developer)) return null;
        Matcher matcher = ENRAGED.matcher(raw.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    static void observeMessage(Minecraft client, String raw, DungeonConfig config,
        DungeonRunStats.DungeonClass selfClass) {
        if (client == null || client.player == null || client.gui == null) return;
        boolean developer = com.github.beng420.kung.runtime.KungDeveloperAccess.allowed();
        String boss = enragedBoss(raw, config, selfClass, client.player.experienceLevel, developer);
        // A matching line that still produces no alert used to vanish without a trace. Record which
        // gate held it, so one saved trace answers "why was it quiet" instead of a guess.
        if (raw != null && ENRAGED.matcher(raw.trim()).matches()) {
            KungDebugRecorder.event("wish-alert", "enraged fired=" + (boss != null)
                + " enabled=" + config.wishAlertEnabled()
                + " class=" + selfClass + " xpLevel=" + client.player.experienceLevel
                + " developer=" + developer + " bypass=" + config.devWishAlertAnyClassEnabled());
        }
        if (boss != null) alert(client, boss + " is enraged");
    }

    static void observeSidebar(Minecraft client, List<String> lines, DungeonConfig config,
        DungeonRunStats.DungeonClass selfClass, LowHealth lowHealth) {
        if (client == null || client.player == null || client.gui == null || config == null) return;
        boolean ready = canWish(config, selfClass, client.player.experienceLevel,
            com.github.beng420.kung.runtime.KungDeveloperAccess.allowed());
        for (String line : lines) {
            String name = lowHealth.observe(DungeonLifecycleSignals.clean(line), config.wishAlertLowHealthPercent(), ready);
            if (name != null) alert(client, name + " is below " + config.wishAlertLowHealthPercent() + "% HP");
        }
    }

    private static void alert(Minecraft client, String reason) {
        McCompat.setTitleTimes(client, 0, 40, 10);
        McCompat.setTitle(client, Component.literal("WISH").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        McCompat.setSubtitle(client, Component.literal(reason).withStyle(ChatFormatting.GRAY));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.6F, 1.0F));
        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.8F, 1.0F));
    }

    /**
     * Teammate HP from the dungeon sidebar, which shows current HP only.
     * ponytail: max HP is the highest value seen this run (everyone starts full). A shield that
     * the sidebar counts as HP would raise that max and make later alerts early; read real max HP
     * from somewhere else if a trace shows that happening.
     */
    static final class LowHealth {
        private final Map<String, Double> maxSeen = new HashMap<>();
        private final Set<String> alerted = new HashSet<>();
        private boolean loggedUnparsed;

        void reset() {
            maxSeen.clear();
            alerted.clear();
            loggedUnparsed = false;
        }

        /** Returns a teammate to alert for: below the threshold, Wish ready, not alerted since they last recovered. */
        String observe(String line, int thresholdPercent, boolean ready) {
            Matcher matcher = TEAMMATE.matcher(line);
            if (!matcher.find()) {
                // The format is not confirmed by a real run yet; one sample shows what to match instead.
                if (!loggedUnparsed && line.contains("❤")) {
                    loggedUnparsed = true;
                    KungDebugRecorder.event("wish-alert", "unparsed line=" + line);
                }
                return null;
            }
            String name = matcher.group(1);
            double hp = Double.parseDouble(matcher.group(2).replace(",", "")) * (matcher.group(3).isEmpty() ? 1 : 1000);
            if (!maxSeen.containsKey(name)) KungDebugRecorder.event("wish-alert", "teammate " + name + " hp=" + hp + " line=" + line);
            double max = maxSeen.merge(name, hp, Math::max);
            if (hp >= max * thresholdPercent / 100.0) {
                alerted.remove(name);
                return null;
            }
            // Held back while Wish charges, so it still fires once Wish is ready and they are still low.
            if (!ready || !alerted.add(name)) return null;
            KungDebugRecorder.event("wish-alert", "low " + name + " hp=" + hp + " max=" + max + " threshold=" + thresholdPercent);
            return name;
        }
    }
}
