package com.github.beng420.kung.feature.misc;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.junit.Test;

public final class FrozenBlazeCaptureTest {
    @Test
    public void linesStopAtTheCapCountWhatWasDroppedAndStillTakeStructureLines() {
        var lines = new FrozenBlazeCapture.Lines(3, 100, 100);
        for (int i = 0; i < 5; i++) lines.add("detail " + i);
        lines.force("MARK stand-still");
        assertEquals(4, lines.size());
        assertEquals(2, lines.dropped());
        assertEquals("detail 0\ndetail 1\ndetail 2\nMARK stand-still\n", lines.text());
    }

    @Test
    public void overlongAndMultiLineTextStaysOneBoundedLine() {
        var lines = new FrozenBlazeCapture.Lines(10, 20, 20);
        lines.add("x".repeat(50));
        lines.add("a\nb\rc");
        assertEquals("x".repeat(17) + "...\na b c\n", lines.text());
    }

    @Test
    public void structureLinesAreClippedFarLaterThanDetailLines() {
        var lines = new FrozenBlazeCapture.Lines(10, 20, 100);
        lines.add("x".repeat(50));
        lines.force("y".repeat(100));
        lines.force("z".repeat(150));
        assertEquals("x".repeat(17) + "...\n" + "y".repeat(100) + "\n" + "z".repeat(97) + "...\n", lines.text());
    }

    @Test
    public void aClipNeverCutsASurrogatePairInHalf() {
        var lines = new FrozenBlazeCapture.Lines(10, 20, 20);
        lines.add("x".repeat(16) + "😀" + "tail");
        lines.add("x".repeat(17) + "😀" + "tail");
        assertEquals("x".repeat(16) + "...\n" + "x".repeat(17) + "...\n", lines.text());
    }

    @Test
    public void aPacketLineWithFortyTypesKeepsEveryTypeAndTheDetailSummaryInFront() {
        List<Class<?>> types = List.of(ConcurrentHashMap.class, ConcurrentLinkedQueue.class, ConcurrentLinkedDeque.class,
            ConcurrentSkipListMap.class, ConcurrentSkipListSet.class, CopyOnWriteArrayList.class, CopyOnWriteArraySet.class,
            CountDownLatch.class, CyclicBarrier.class, Semaphore.class, Exchanger.class, Phaser.class, ForkJoinPool.class,
            ForkJoinTask.class, CompletableFuture.class, FutureTask.class, ThreadPoolExecutor.class,
            ScheduledThreadPoolExecutor.class, LinkedBlockingQueue.class, LinkedBlockingDeque.class,
            ArrayBlockingQueue.class, PriorityBlockingQueue.class, DelayQueue.class, SynchronousQueue.class,
            LinkedTransferQueue.class, Executors.class, TimeUnit.class, ThreadLocalRandom.class, TimeoutException.class,
            ExecutionException.class, CancellationException.class, RejectedExecutionException.class,
            BrokenBarrierException.class, CompletionException.class, AtomicInteger.class, AtomicLong.class,
            AtomicBoolean.class, AtomicReference.class, LongAdder.class, DoubleAdder.class);
        assertEquals(40, types.size());
        var in = new HashMap<Class<?>, int[]>();
        var out = new HashMap<Class<?>, int[]>();
        for (var type : types) FrozenBlazeCapture.count(in, type);
        FrozenBlazeCapture.count(out, String.class);
        var budget = new FrozenBlazeCapture.Budget(FrozenBlazeCapture.DETAILS_PER_SECOND);
        for (int i = 0; i < 31; i++) budget.room("DATA", FrozenBlazeCapture.DETAILS_PER_SECOND);

        String packets = FrozenBlazeCapture.packets(budget, in, out);
        assertTrue(packets.length() > FrozenBlazeCapture.MAX_LINE_LENGTH);
        var lines = new FrozenBlazeCapture.Lines(FrozenBlazeCapture.MAX_LINES, FrozenBlazeCapture.MAX_LINE_LENGTH,
            FrozenBlazeCapture.MAX_FORCED_LINE_LENGTH);
        lines.force(packets);
        assertEquals(packets + "\n", lines.text());
        assertTrue(packets.startsWith("PKTS detail=31 more=DATA+1 in[total=40 "));
        assertTrue(packets.endsWith(" out[total=1 String=1]"));
        for (var type : types) assertTrue(type.getSimpleName(), packets.contains(" " + type.getSimpleName() + "=1"));
    }

    @Test
    public void aBurstOfDataLinesWritesTheBudgetAndCountsTheRest() {
        var budget = new FrozenBlazeCapture.Budget(30);
        assertEquals("quiet", budget.summary());
        int written = 0;
        for (int i = 0; i < 100; i++) if (budget.room("DATA", 30)) written++;
        assertEquals(30, written);
        assertEquals("detail=100 more=DATA+70", budget.summary());
        budget.reset();
        assertEquals("quiet", budget.summary());
        assertTrue(budget.room("DATA", 30));
    }

    @Test
    public void particlesKeepTheirKindLimitAndEveryKindSharesTheTotal() {
        var budget = new FrozenBlazeCapture.Budget(30);
        int particles = 0, data = 0;
        for (int i = 0; i < 10; i++) if (budget.room("PARTICLE minecraft:flame", 8)) particles++;
        for (int i = 0; i < 100; i++) if (budget.room("DATA", 30)) data++;
        assertEquals(8, particles);
        assertEquals(22, data);
        assertEquals("detail=110 more=DATA+78, PARTICLE minecraft:flame+2", budget.summary());
        assertEquals("DATA", FrozenBlazeCapture.kindOf("DATA self id=3 Byte=1"));
        assertEquals("ERROR", FrozenBlazeCapture.kindOf("ERROR"));
    }

    @Test
    public void stampPadsClientTicksMillisecondsAndServerTicks() {
        assertEquals("[t=0007 +000350ms s=0006] ", FrozenBlazeCapture.stamp(7, 350, 6));
        assertEquals("[t=6000 +300000ms s=5990] ", FrozenBlazeCapture.stamp(6000, 300_000, 5990));
    }

    @Test
    public void histogramListsTheBusiestTypeFirstAndKeepsNestedNames() {
        var counts = new HashMap<Class<?>, int[]>();
        assertEquals("none", FrozenBlazeCapture.histogram(counts));
        FrozenBlazeCapture.count(counts, String.class);
        FrozenBlazeCapture.count(counts, Map.Entry.class);
        FrozenBlazeCapture.count(counts, Map.Entry.class);
        FrozenBlazeCapture.count(counts, Integer.class);
        assertEquals("total=4 Map.Entry=2 Integer=1 String=1", FrozenBlazeCapture.histogram(counts));
    }

    @Test
    public void eachKindKeepsItsFirstLinesAndTheOverflowIsSummedUp() {
        var budget = new HashMap<String, int[]>();
        int kept = 0;
        for (int i = 0; i < 10; i++) if (FrozenBlazeCapture.within(budget, "PARTICLE minecraft:flame", 8)) kept++;
        assertEquals(8, kept);
        assertTrue(FrozenBlazeCapture.within(budget, "SOUND minecraft:x", 8));
        assertEquals(" more=PARTICLE minecraft:flame+2", FrozenBlazeCapture.overflow(budget, 8));
        assertEquals("", FrozenBlazeCapture.overflow(new HashMap<>(), 8));
    }

    @Test
    public void relativePositionShowsTheOffsetAndTheHorizontalDistance() {
        assertEquals("rel=(+0.30,-1.00,+0.40) h=0.50", FrozenBlazeCapture.relative(0.3, -1.0, 0.4));
    }

    @Test
    public void armorSummaryTellsUnreadableArmorFromBlazeSets() {
        assertEquals("ids=unreadable", FrozenBlazeCapture.armorSummary(List.of("", "", "", "")));
        assertEquals("ids=[FROZEN_BLAZE_HELMET, FROZEN_BLAZE_CHESTPLATE, FROZEN_BLAZE_LEGGINGS, FROZEN_BLAZE_BOOTS]"
                + " frozenBlaze=4 blaze=0 set=Frozen Blaze",
            FrozenBlazeCapture.armorSummary(List.of("FROZEN_BLAZE_HELMET", "FROZEN_BLAZE_CHESTPLATE",
                "FROZEN_BLAZE_LEGGINGS", "FROZEN_BLAZE_BOOTS")));
        assertEquals("ids=[BLAZE_HELMET, -, BLAZE_LEGGINGS, BLAZE_BOOTS] frozenBlaze=0 blaze=3 set=-",
            FrozenBlazeCapture.armorSummary(List.of("BLAZE_HELMET", "", "BLAZE_LEGGINGS", "BLAZE_BOOTS")));
    }

    @Test
    public void onlyTheFourAuraWordsSelectChatAndTitleText() {
        assertTrue(FrozenBlazeCapture.hasAuraWord("Frozen Blazing Aura activated"));
        assertTrue(FrozenBlazeCapture.hasAuraWord("You are Blazing"));
        assertFalse(FrozenBlazeCapture.hasAuraWord("Welcome to Hypixel SkyBlock"));
    }

    @Test
    public void secondsStayInsideTheCommandLimits() {
        assertEquals(1, FrozenBlazeCapture.clampSeconds(0));
        assertEquals(120, FrozenBlazeCapture.clampSeconds(120));
        assertEquals(FrozenBlazeCapture.MAX_SECONDS, FrozenBlazeCapture.clampSeconds(9999));
    }

    @Test
    public void footerSaysWhenLinesWereDropped() {
        assertEquals("END reason=stop ticks=240 ms=12000 lines=50 dropped=0",
            FrozenBlazeCapture.footer("stop", 240, 12_000, 50, 0));
        assertTrue(FrozenBlazeCapture.footer("auto", 2400, 120_000, 6100, 17).contains("dropped=17 INCOMPLETE"));
    }
}
