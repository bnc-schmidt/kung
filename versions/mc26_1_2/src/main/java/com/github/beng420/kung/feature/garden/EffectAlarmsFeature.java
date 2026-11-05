package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.config.category.GardenAlarmsConfig;
import com.github.beng420.kung.feature.ConfigurableFeature;
import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.HypixelInstanceTracker;
import com.github.beng420.kung.skyblock.HypixelLocation;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;

/**
 * The No FF Buff and No Spray alarms of Garden Alarms (the Max Visitor Alarm lives in {@link VisitorAlarmFeature}).
 * Both ring once per missing effect, and only while the player farms a garden plot: see {@link FarmingActivity}.
 */
public final class EffectAlarmsFeature extends ConfigurableFeature<GardenAlarmsConfig> {
    public static final EffectAlarmsFeature INSTANCE = new EffectAlarmsFeature();
    private static final int POLL_TICKS = 5;
    private static final int TONES = 4;
    private static final long TONE_GAP_MILLIS = 250;

    private final FarmingActivity farming = new FarmingActivity();
    private final AlarmMuteKey muteKey = new AlarmMuteKey();
    private final GardenCropTracker crops = new GardenCropTracker();
    private final MissingEffectAlarm ffAlarm = new MissingEffectAlarm();
    private final MissingEffectAlarm sprayAlarm = new MissingEffectAlarm();
    private long epoch = -1;
    private String plot;
    private boolean greenhouse;
    private int polls;
    private int tonesLeft;
    private long nextTone;
    private boolean highTone;

    private EffectAlarmsFeature() { super(config -> config.gardenAlarms); }

    @Override
    protected void onInitialize() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    @Override
    public boolean isEnabled() { return initialized() && config().enabled() && (config().noFfBuff() || config().noSpray()); }

    private void tick(Minecraft client) {
        if (!isEnabled()) {
            if (plot != null || tonesLeft > 0) onReset();
            return;
        }
        long now = now();
        if (muteKey.press(client, config().muteKeybind())) tonesLeft = 0;
        if (tonesLeft > 0 && now >= nextTone) {
            tonesLeft--;
            highTone = !highTone;
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(),
                highTone ? 1.6F : 1.0F, config().volume() / 100F));
            nextTone = now + TONE_GAP_MILLIS;
        }
        if (++polls < POLL_TICKS) return;
        polls = 0;
        poll(client, now);
    }

    private void poll(Minecraft client, long now) {
        var context = HypixelInstanceTracker.INSTANCE;
        if (!HypixelLocation.onHypixel(client) || client.player == null) {
            onReset();
            return;
        }
        if (epoch != context.instanceEpoch()) {
            onReset();
            epoch = context.instanceEpoch();
        }
        boolean ownGarden = context.tracking()
            && VisitorQueue.ownGarden(context.instanceLine(), context.sidebarLines(), context.visibleLines());
        if (!ownGarden) {
            plot = null;
            greenhouse = false;
            farming.reset();
            return;
        }
        var reading = GardenEffects.read(context.sidebarLines(), context.visibleLines());
        if (!Objects.equals(plot, reading.plot())) {
            plot = reading.plot();
            farming.reset();
            sprayAlarm.rearm();
        }
        greenhouse = GreenhouseLocator.current(client, false).isPresent();
        boolean active = FarmingActivity.farmingInPlot(greenhouse, plot, farming.active(now));
        if (config().noFfBuff() && ffAlarm.update(active, reading.ffBuff())) {
            ring(client, "No Farming Fortune buff: the Pesthunter bonus is inactive.", "ff-buff");
        }
        if (config().noSpray() && sprayAlarm.update(active, reading.sprayed())) {
            ring(client, "Plot " + plot + " has no spray.", "spray");
        }
    }

    private void ring(Minecraft client, String text, String reason) {
        tonesLeft = TONES;
        nextTone = 0;
        client.player.sendSystemMessage(KungMessages.warning("Garden", text));
        KungDebugRecorder.event("garden-alarm", reason + " plot=" + plot);
    }

    public static void observeCropClick(BlockPos pos) {
        if (!INSTANCE.isEnabled() || INSTANCE.plot == null || INSTANCE.greenhouse) return;
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.player.isSpectator()) return;
        if (INSTANCE.crops.observe(pos, client.level.getBlockState(pos))) INSTANCE.farming.harvest(now());
    }

    @Override
    protected void onReset() {
        plot = null;
        greenhouse = false;
        epoch = -1;
        polls = 0;
        tonesLeft = 0;
        farming.reset();
        crops.reset();
        ffAlarm.rearm();
        sprayAlarm.rearm();
    }

    private static long now() { return System.nanoTime() / 1_000_000; }
}
