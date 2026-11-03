package com.github.beng420.kung.feature.safari;

import com.github.beng420.kung.feature.safari.SafariThrowRecorder.Body;
import com.github.beng420.kung.feature.safari.SafariThrowRecorder.Launch;
import com.github.beng420.kung.feature.safari.SafariThrowRecorder.Vec;
import com.github.beng420.kung.util.KungDebugRecorder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class SafariThrowRecorderTest {
    private final List<String> lines = new ArrayList<>();
    private final SafariThrowRecorder recorder = new SafariThrowRecorder(lines::add);
    private final FakeWorld world = new FakeWorld();

    @After
    public void clearRecorder() { KungDebugRecorder.clear(); }

    @Test
    public void flightIsWrittenAsLaunchFirstSightingPathAndEnd() {
        recorder.use(world, launch());
        // Parabola from the eye: two blocks a tick forward, falling faster each tick, gone after 40 ticks.
        for (int tick = 0; tick < 45; tick++) {
            if (tick == 2) world.put(body(7, "armor_stand", new Vec(0.5, 65.62, 0.5), 0));
            if (tick > 2 && tick < 42) world.move(7, new Vec(0.5, 65.62 - 0.03 * (tick - 2) * (tick - 2) / 2, 0.5 + 2 * (tick - 2)));
            if (tick == 42) world.remove(7);
            recorder.tick(world);
        }
        assertTrue(lines.get(0), lines.get(0).startsWith("throw=1 use tick=0 item=\"Critter Capsule\" pos=0.50000,64.00000,0.50000"));
        assertTrue(lines.get(0).contains("yaw=-12.5 pitch=33.25"));
        assertTrue(lines.get(0).contains("ping=42"));
        assertTrue(lines.get(1), lines.get(1).startsWith("throw=1 track=1 first tick=+3 age=0 id=7 type=armor_stand"));
        assertTrue(lines.get(1).contains("fromEye=0.000 "));
        List<String> paths = lines.stream().filter(line -> line.contains(" path ")).toList();
        assertEquals(3, paths.size());
        assertTrue(paths.get(0), paths.get(0).contains("path from=+3 n=18 p=0.50000,65.62000,0.50000;"));
        assertTrue(paths.get(2), paths.get(2).contains("n=4 "));
        String end = lines.get(lines.size() - 1);
        assertTrue(end, end.contains("track=1 end tick=+43 reason=gone samples=40"));
        assertTrue(end, end.contains("blockAt=stone@"));
        for (String line : lines) assertTrue(line.length() + " " + line, line.length() < 800);
    }

    @Test
    public void onlyYoungEntitiesNearTheThrowerAreClaimed() {
        world.put(body(1, "zombie", new Vec(1, 64, 1), 400));
        world.put(body(2, "armor_stand", new Vec(30, 64, 30), 0));
        world.put(body(3, "snowball", new Vec(1, 65, 1), 1));
        recorder.use(world, launch());
        recorder.tick(world);
        recorder.tick(world);
        assertEquals(1L, lines.stream().filter(line -> line.contains(" first ")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("id=3 type=snowball")));
        assertTrue(lines.get(0).contains("zombie#1@1.00000,64.00000,1.00000"));
    }

    @Test
    public void throwWithoutEntityIsLoggedOnceAndMessagesAreTagged() {
        recorder.use(world, launch());
        recorder.message("You threw a Critter Capsule at the Gazer!");
        recorder.message("<Steve> hello");
        for (int tick = 0; tick < SafariThrowRecorder.WINDOW_TICKS + 5; tick++) recorder.tick(world);
        recorder.message("CAPTURE! You caught a Gazer and gained a Gazer Shard!");
        assertEquals(1L, lines.stream().filter(line -> line.contains("no-entity")).count());
        assertEquals(2L, lines.stream().filter(line -> line.contains(" msg ")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("msg tick=+0 text=\"You threw a Critter Capsule")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("hello")));
    }

    @Test
    public void secondUseInTheSameTickIsTheSameThrow() {
        recorder.use(world, launch());
        recorder.use(world, launch());
        assertEquals(1L, lines.stream().filter(line -> line.contains(" use ")).count());
        recorder.tick(world);
        recorder.use(world, launch());
        assertTrue(lines.get(lines.size() - 1).startsWith("throw=2 use tick=1"));
    }

    @Test
    public void overlappingThrowsKeepTheirOwnEntities() {
        recorder.use(world, launch());
        recorder.tick(world);
        world.put(body(10, "armor_stand", new Vec(0.5, 65.6, 0.5), 0));
        recorder.tick(world);
        recorder.use(world, launch());
        recorder.tick(world);
        world.put(body(11, "armor_stand", new Vec(0.5, 65.6, 0.5), 0));
        recorder.tick(world);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("throw=1 track=1 first") && line.contains("id=10")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("throw=2 track=1 first") && line.contains("id=11")));
    }

    @Test
    public void longFlightsStopAtTheTrackLimit() {
        recorder.use(world, launch());
        world.put(body(5, "egg", new Vec(0.5, 65.6, 0.5), 0));
        for (int tick = 0; tick < SafariThrowRecorder.MAX_TRACK_TICKS + 2; tick++) recorder.tick(world);
        assertTrue(lines.get(lines.size() - 1).contains("reason=timeout samples=" + SafariThrowRecorder.MAX_TRACK_TICKS));
    }

    @Test
    public void throwLinesReachTheSharedTraceAndSurviveGeneralTraffic() {
        KungDebugRecorder.clear();
        SafariThrowRecorder shared = new SafariThrowRecorder(line -> KungDebugRecorder.event("safari-throw", line));
        shared.use(world, launch());
        for (int event = 0; event < 2600; event++) KungDebugRecorder.event("test-traffic", "event=" + event);
        String dump = KungDebugRecorder.dump();
        assertTrue(dump.contains("[safari-throw] throw=1 use tick=0"));
        assertTrue(dump.contains("safari-throw seen=1 kept=1"));
    }

    private static Launch launch() {
        return new Launch("Critter Capsule", new Vec(0.5, 64, 0.5), 65.62, -12.5F, 33.25F, new Vec(0, -0.0784, 0),
            true, false, 42);
    }

    private static Body body(int id, String type, Vec pos, int age) {
        return new Body(id, type, "", "", pos, new Vec(0, 0, 0), 0.25, 0.25, age);
    }

    private static final class FakeWorld implements SafariThrowRecorder.World {
        private final Map<Integer, Body> bodies = new HashMap<>();

        void put(Body body) { bodies.put(body.id(), body); }

        void remove(int id) { bodies.remove(id); }

        void move(int id, Vec pos) {
            Body old = bodies.get(id);
            bodies.put(id, new Body(id, old.type(), old.item(), old.name(), pos, old.velocity(), old.width(),
                old.height(), old.age() + 1));
        }

        @Override
        public Body get(int id) { return bodies.get(id); }

        @Override
        public List<Body> near(Vec center, double radius) {
            return bodies.values().stream().filter(body -> body.pos().distanceTo(center) <= radius).toList();
        }

        @Override
        public String block(Vec pos) { return "stone@" + (int) Math.floor(pos.x()); }
    }
}
