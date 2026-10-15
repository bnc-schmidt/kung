package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.KungMod;
import com.github.beng420.kung.config.category.MiscConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

public final class CustomSoundsFeature extends ConfigurableFeature<MiscConfig> {
    public static final CustomSoundsFeature INSTANCE = new CustomSoundsFeature();
    private static final String BUNDLED_SOUND_RESOURCE_PREFIX = "/assets/kung/sounds/custom/";
    private static final List<String> BUNDLED_SOUND_NAMES = List.of(
        "kung_arrow_ping.wav",
        "kung_arrow_plink.wav",
        "kung_wither_fade.wav",
        "kung_wither_chime.wav"
    );
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("wav", "aif", "aiff", "au", "mp3");
    private static final ExecutorService SOUND_EXECUTOR = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "Kung Sound Decode");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final CustomSoundPlayer SOUND_PLAYER = new CustomSoundPlayer(message ->
        KungDebugRecorder.event("custom-sounds", message));
    private static final AtomicLong PLAYBACK_GENERATION = new AtomicLong();
    private static final WitherShieldSoundTimer SHIELD_TIMER = new WitherShieldSoundTimer();
    private static final Map<String, CachedSound> SOUND_CACHE = new ConcurrentHashMap<>();
    private static volatile List<String> availableSounds = List.of();
    private static volatile long lastScanMillis;
    private static Object lastLevel;
    private static boolean wasEnabled;

    private CustomSoundsFeature() {
        super(config -> config.misc);
    }

    @Override
    protected void onInitialize() {
        installBundledSounds();
        refreshSoundIndex();
        ClientTickEvents.END_CLIENT_TICK.register(CustomSoundsFeature::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> observeMessage(message));
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide() && player == Minecraft.getInstance().player) {
                observeItemUse(player.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
    }

    @Override
    public boolean isEnabled() {
        return config().customSoundsEnabled();
    }

    @Override
    protected void onReset() {
        resetPlayback();
    }

    @Override
    protected void onShutdown() {
        resetPlayback();
    }

    public static void observeSoundEvent(SoundEvent sound) {
        if (!enabled() || sound == null) {
            return;
        }
        Identifier id = BuiltInRegistries.SOUND_EVENT.getKey(sound);
        if (id == null || !"minecraft".equals(id.getNamespace())) {
            return;
        }
        String path = id.getPath();
        if ("entity.arrow.hit".equals(path) || "entity.arrow.hit_player".equals(path)) {
            playArrowHit();
        }
    }

    /** Files in the sounds folder; the menu asks every frame, so the folder is rescanned at most every 2 s. */
    public static List<String> availableSounds() {
        if (System.currentTimeMillis() - lastScanMillis > 2000) refreshSoundIndex();
        return availableSounds;
    }

    public static Path soundsFolder() {
        return soundsDirectory();
    }

    /** Opens the folder in the system file browser (Explorer on Windows). */
    public static void openSoundsFolder() {
        refreshSoundIndex();
        net.minecraft.util.Util.getPlatform().openPath(soundsDirectory());
    }

    public static void refreshSoundIndex() {
        lastScanMillis = System.currentTimeMillis();
        try {
            Path directory = soundsDirectory();
            Files.createDirectories(directory);
            List<String> next = new ArrayList<>();
            try (var files = Files.list(directory)) {
                files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(CustomSoundsFeature::isSupportedSoundFile)
                    .sorted(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)))
                    .forEach(next::add);
            }
            availableSounds = List.copyOf(next);
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Failed to scan custom sound directory.", exception);
            availableSounds = List.of();
        }
    }

    private static void installBundledSounds() {
        try {
            Path directory = soundsDirectory();
            Files.createDirectories(directory);
            for (String soundName : BUNDLED_SOUND_NAMES) {
                Path target = directory.resolve(soundName);
                if (Files.exists(target)) {
                    continue;
                }
                try (InputStream stream = CustomSoundsFeature.class.getResourceAsStream(
                    BUNDLED_SOUND_RESOURCE_PREFIX + soundName
                )) {
                    if (stream == null) {
                        KungMod.LOGGER.warn("Bundled custom sound {} was not found in the jar.", soundName);
                        continue;
                    }
                    Files.copy(stream, target);
                }
            }
        } catch (IOException exception) {
            KungMod.LOGGER.warn("Failed to install bundled custom sounds.", exception);
        }
    }

    public static void playArrowHit() {
        playConfigured(INSTANCE.config().customArrowHitSounds(), CustomSoundEvent.ARROW_HIT);
    }


    public static void playWitherShieldExpire() {
        playConfigured(INSTANCE.config().customWitherShieldExpireSounds(), CustomSoundEvent.WITHER_SHIELD_EXPIRE);
    }

    /** The sound screen's Test: plays even while Custom Sounds is off, so sounds can be tuned first. */
    public static void testArrowHit() {
        for (String name : configuredSoundNames(INSTANCE.config().customArrowHitSounds())) playSingle(name, CustomSoundEvent.ARROW_HIT, false);
    }

    public static void testWitherShieldExpire() {
        for (String name : configuredSoundNames(INSTANCE.config().customWitherShieldExpireSounds())) {
            playSingle(name, CustomSoundEvent.WITHER_SHIELD_EXPIRE, false);
        }
    }

    public static List<String> configuredSoundNames(String configuredSounds) {
        if (configuredSounds == null || configuredSounds.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String soundName : configuredSounds.split(",")) {
            String normalized = normalizeSoundName(soundName);
            if (!normalized.isBlank() && !names.contains(normalized)) {
                names.add(normalized);
            }
        }
        return List.copyOf(names);
    }

    private static void tick(Minecraft client) {
        boolean active = enabled();
        if (client.level != lastLevel || (wasEnabled && !active)) {
            resetPlayback();
        }
        lastLevel = client.level;
        wasEnabled = active;
        if (active && client.player != null && SHIELD_TIMER.expire(System.nanoTime())) {
            KungDebugRecorder.event("custom-sounds", "wither-shield-expire source=item-use-estimate");
            playWitherShieldExpire();
        }
    }

    private static void resetPlayback() {
        SHIELD_TIMER.finish();
        synchronized (SOUND_PLAYER) {
            PLAYBACK_GENERATION.incrementAndGet();
            SOUND_PLAYER.stop();
        }
    }

    private static void observeItemUse(ItemStack stack) {
        if (!enabled() || stack == null || stack.isEmpty()) {
            return;
        }
        String itemName = clean(stack.getHoverName().getString());
        if (itemName.contains("hyperion")
            || itemName.contains("astraea")
            || itemName.contains("scylla")
            || itemName.contains("valkyrie")
            || itemName.contains("necron's blade")
            || itemName.contains("necrons blade")) {
            if (SHIELD_TIMER.expire(System.nanoTime())) {
                playWitherShieldExpire();
            }
            if (SHIELD_TIMER.start(System.nanoTime())) {
                KungDebugRecorder.event("custom-sounds", "scheduled wither-shield-expire in=5s source=item-use-estimate");
            }
        }
    }

    private static void observeMessage(Component message) {
        if (!enabled() || message == null) {
            return;
        }
        String text = clean(message.getString());
        if (text.contains("wither shield")
            && (text.contains("expired") || text.contains("ended") || text.contains("ran out"))
            && SHIELD_TIMER.finish()) {
            playWitherShieldExpire();
        }
    }

    private static void playConfigured(String configuredSounds, CustomSoundEvent event) {
        if (!enabled() || configuredSounds == null || configuredSounds.isBlank()) {
            return;
        }
        for (String soundName : configuredSoundNames(configuredSounds)) {
            playSingle(soundName, event);
        }
    }

    private static void playSingle(String soundName, CustomSoundEvent event) {
        playSingle(soundName, event, true);
    }

    private static void playSingle(String soundName, CustomSoundEvent event, boolean requireEnabled) {
        if (requireEnabled && !enabled()) {
            return;
        }
        String normalized = normalizeSoundName(soundName);
        if (normalized.isBlank()) {
            return;
        }
        MiscConfig config = INSTANCE.config();
        int volumeTenths = event == CustomSoundEvent.ARROW_HIT
            ? config.customArrowHitVolumeTenths() : config.customWitherShieldExpireVolumeTenths();
        if (volumeTenths <= 0) {
            return;
        }
        int pitchHundredths = event == CustomSoundEvent.ARROW_HIT
            ? config.customArrowHitPitchHundredths() : config.customWitherShieldExpirePitchHundredths();
        float volume = Math.clamp(volumeTenths / 10.0f, 0.0f, 5.0f);
        float pitch = Math.clamp(pitchHundredths / 100.0f, 0.25f, 3.0f);
        long generation = PLAYBACK_GENERATION.get();
        long requestedAt = System.nanoTime();
        CompletableFuture.runAsync(() -> playSound(normalized, volume, pitch, generation, requestedAt), SOUND_EXECUTOR);
    }

    private static void playSound(String soundName, float volume, float pitch, long generation, long requestedAt) {
        try {
            CachedSound sound = cachedSound(soundName);
            if (sound == null || sound.sample().mono().length == 0) {
                KungDebugRecorder.event("custom-sounds", "file unavailable: " + soundName);
                return;
            }
            synchronized (SOUND_PLAYER) {
                if (generation != PLAYBACK_GENERATION.get() || System.nanoTime() - requestedAt > 1_000_000_000L) {
                    KungDebugRecorder.event("custom-sounds", "discarded stale playback: " + soundName);
                    return;
                }
                SOUND_PLAYER.play(sound.sample(), volume, pitch);
            }
            KungDebugRecorder.event("custom-sounds", "play " + soundName + " volume=" + volume + " pitch=" + pitch);
        } catch (IOException | UnsupportedAudioFileException | IllegalArgumentException exception) {
            KungDebugRecorder.event("custom-sounds", "decode failed " + soundName + ": " + exception.getMessage());
            KungMod.LOGGER.warn("Failed to play custom sound {}.", soundName, exception);
        }
    }

    private static CachedSound cachedSound(String soundName) throws IOException, UnsupportedAudioFileException {
        String normalized = normalizeSoundName(soundName);
        Path path = soundsDirectory().resolve(normalized).normalize();
        if (!path.startsWith(soundsDirectory()) || !Files.isRegularFile(path) || !isSupportedSoundFile(normalized)) {
            return null;
        }
        long modified = Files.getLastModifiedTime(path).toMillis();
        CachedSound cached = SOUND_CACHE.get(normalized);
        if (cached != null && cached.modifiedAtMillis() == modified) {
            return cached;
        }

        try (AudioInputStream input = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat baseFormat = input.getFormat();
            AudioFormat decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(),
                false
            );
            try (AudioInputStream decoded = AudioSystem.getAudioInputStream(decodedFormat, input)) {
                byte[] pcm = decoded.readAllBytes();
                CachedSound next = new CachedSound(modified,
                    CustomSoundMixer.decodePcm(pcm, decodedFormat.getChannels(), decodedFormat.getSampleRate()));
                SOUND_CACHE.put(normalized, next);
                return next;
            }
        }
    }

    private static Path soundsDirectory() {
        return com.github.beng420.kung.runtime.KungPaths.fileLayout().soundsDirectory();
    }

    private static boolean enabled() {
        return INSTANCE.isEnabled();
    }

    private static boolean isSupportedSoundFile(String name) {
        String normalized = normalizeSoundName(name);
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || dot == normalized.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(normalized.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String normalizeSoundName(String value) {
        if (value == null) {
            return "";
        }
        String name = value.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    private static String clean(String text) {
        String stripped = ChatFormatting.stripFormatting(text == null ? "" : text);
        return (stripped == null ? "" : stripped).toLowerCase(Locale.ROOT);
    }

    private record CachedSound(long modifiedAtMillis, CustomSoundMixer.Sample sample) {
    }

    private enum CustomSoundEvent {
        ARROW_HIT,
        WITHER_SHIELD_EXPIRE
    }
}
