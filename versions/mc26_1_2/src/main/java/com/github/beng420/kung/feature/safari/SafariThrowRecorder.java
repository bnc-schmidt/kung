package com.github.beng420.kung.feature.safari;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bookkeeping behind the Safari throw tracker, with no game classes so tests can cover it. One throw starts at the
 * right-click that uses a capsule. The recorder then claims entities that appear near the thrower within a few
 * ticks (the entity type is not known yet, so any young entity counts), follows each one tick by tick and writes
 * the launch, the flight and the end as plain lines. Fitting the real physics from those lines is a later step.
 */
final class SafariThrowRecorder {
    static final int WINDOW_TICKS = 30;
    /** A claimed entity must have been on the client for at most this many ticks. */
    static final int NEW_ENTITY_AGE = 4;
    static final double SPAWN_RADIUS = 4.0;
    static final double ENV_RADIUS = 6.0;
    static final int ENV_COUNT = 5;
    static final int MAX_TRACKS = 3;
    static final int MAX_TRACK_TICKS = 240;
    static final int SAMPLES_PER_LINE = 18;
    /** How long after its last activity a throw still takes capture and escape messages. */
    static final int MESSAGE_TICKS = 160;

    record Vec(double x, double y, double z) {
        Vec minus(Vec other) { return new Vec(x - other.x, y - other.y, z - other.z); }
        Vec plus(Vec other) { return new Vec(x + other.x, y + other.y, z + other.z); }
        double distanceTo(Vec other) {
            Vec delta = minus(other);
            return Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z);
        }

        @Override
        public String toString() { return fixed(x, 5) + "," + fixed(y, 5) + "," + fixed(z, 5); }
    }

    record Body(int id, String type, String item, String name, Vec pos, Vec velocity,
                double width, double height, int age) { }

    record Launch(String item, Vec pos, double eyeY, float yaw, float pitch, Vec velocity,
                  boolean onGround, boolean sneaking, int ping) {
        Vec eye() { return new Vec(pos.x(), eyeY, pos.z()); }
    }

    /** What the recorder asks the game; entities other than the thrower only. */
    interface World {
        /** The live entity with this id, or null once it is gone. */
        Body get(int id);

        List<Body> near(Vec center, double radius);

        String block(Vec pos);
    }

    private static final class Throw {
        final int number;
        final long useTick;
        final Vec eye;
        final List<Track> tracks = new ArrayList<>();
        long lastActivity;
        boolean noEntityLogged;

        Throw(int number, long useTick, Vec eye) {
            this.number = number;
            this.useTick = useTick;
            this.eye = eye;
            this.lastActivity = useTick;
        }

        boolean tracking() { return tracks.stream().anyMatch(track -> !track.ended); }
    }

    private static final class Track {
        final int number;
        final int entityId;
        final List<Vec> pending = new ArrayList<>();
        long pendingFrom;
        Vec last;
        Vec prev;
        int samples;
        String env = "[]";
        boolean ended;

        Track(int number, int entityId) {
            this.number = number;
            this.entityId = entityId;
        }
    }

    private final Consumer<String> sink;
    private final List<Throw> throwing = new ArrayList<>();
    private final Set<Integer> claimed = new HashSet<>();
    private long now;
    private long lastUseTick = Long.MIN_VALUE;
    private int throwCount;

    SafariThrowRecorder(Consumer<String> sink) { this.sink = sink; }

    void use(World world, Launch launch) {
        // The item and the block callback can both report the same right-click.
        if (lastUseTick == now) return;
        lastUseTick = now;
        Throw thrown = new Throw(++throwCount, now, launch.eye());
        throwing.add(thrown);
        sink.accept(prefix(thrown) + "use tick=" + now + " item=\"" + quote(launch.item()) + "\" pos=" + launch.pos()
            + " eyeY=" + fixed(launch.eyeY(), 5) + " yaw=" + launch.yaw() + " pitch=" + launch.pitch()
            + " vel=" + launch.velocity() + " ground=" + flag(launch.onGround()) + " sneak=" + flag(launch.sneaking())
            + " ping=" + launch.ping() + " env=" + env(world, thrown.eye, -1));
    }

    void tick(World world) {
        now++;
        claim(world);
        for (Throw thrown : throwing) {
            for (Track track : thrown.tracks) {
                if (!track.ended) advance(world, thrown, track);
            }
            if (thrown.tracks.isEmpty() && !thrown.noEntityLogged && now - thrown.useTick > WINDOW_TICKS) {
                thrown.noEntityLogged = true;
                sink.accept(prefix(thrown) + "no-entity window=" + WINDOW_TICKS);
            }
        }
        Iterator<Throw> iterator = throwing.iterator();
        while (iterator.hasNext()) {
            Throw thrown = iterator.next();
            boolean decided = now - thrown.useTick > WINDOW_TICKS;
            if (decided && !thrown.tracking() && now - thrown.lastActivity > MESSAGE_TICKS) iterator.remove();
        }
        if (throwing.isEmpty()) claimed.clear();
    }

    /** Capsule, capture and escape lines go to the latest throw that is still fresh. */
    void message(String text) {
        if (throwing.isEmpty() || text == null) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("capsule") && !lower.contains("capture") && !lower.contains("critter")) return;
        Throw thrown = throwing.get(throwing.size() - 1);
        String clipped = text.length() > 200 ? text.substring(0, 200) : text;
        sink.accept(prefix(thrown) + "msg tick=" + rel(thrown) + " text=\"" + quote(clipped) + "\"");
    }

    void reset() {
        throwing.clear();
        claimed.clear();
        lastUseTick = Long.MIN_VALUE;
    }

    private void claim(World world) {
        for (Throw thrown : throwing) {
            if (now - thrown.useTick > WINDOW_TICKS) continue;
            for (Body body : world.near(thrown.eye, SPAWN_RADIUS)) {
                if (body.age() > NEW_ENTITY_AGE || !claimed.add(body.id())) continue;
                // The oldest throw without an entity yet owns the newcomer; extra entities join the latest one.
                Throw owner = oldestWithoutTrack();
                if (owner == null) owner = thrown;
                if (owner.tracks.size() >= MAX_TRACKS) continue;
                start(world, owner, body);
            }
        }
    }

    private Throw oldestWithoutTrack() {
        for (Throw thrown : throwing) {
            if (now - thrown.useTick <= WINDOW_TICKS && thrown.tracks.isEmpty()) return thrown;
        }
        return null;
    }

    private void start(World world, Throw thrown, Body body) {
        Track track = new Track(thrown.tracks.size() + 1, body.id());
        thrown.tracks.add(track);
        thrown.lastActivity = now;
        sink.accept(prefix(thrown) + "track=" + track.number + " first tick=" + rel(thrown) + " age=" + body.age()
            + " id=" + body.id() + " type=" + body.type() + " item=" + body.item() + " name=\"" + quote(body.name())
            + "\" pos=" + body.pos() + " vel=" + body.velocity() + " size=" + size(body)
            + " fromEye=" + fixed(body.pos().distanceTo(thrown.eye), 3) + " env=" + env(world, body.pos(), body.id()));
    }

    private void advance(World world, Throw thrown, Track track) {
        Body body = world.get(track.entityId);
        if (body == null) {
            end(world, thrown, track, "gone");
            return;
        }
        if (track.pending.isEmpty()) track.pendingFrom = now - thrown.useTick;
        track.pending.add(body.pos());
        track.prev = track.last;
        track.last = body.pos();
        track.samples++;
        track.env = env(world, body.pos(), body.id());
        thrown.lastActivity = now;
        if (track.pending.size() >= SAMPLES_PER_LINE) flush(thrown, track);
        if (track.samples >= MAX_TRACK_TICKS) end(world, thrown, track, "timeout");
    }

    private void end(World world, Throw thrown, Track track, String reason) {
        flush(thrown, track);
        track.ended = true;
        thrown.lastActivity = now;
        String block = "?";
        String ahead = "?";
        if (track.last != null) {
            block = world.block(track.last);
            // One more step along the last move, which is where the next tick would have taken it.
            Vec step = track.prev == null ? new Vec(0, 0, 0) : track.last.minus(track.prev);
            ahead = world.block(track.last.plus(step));
        }
        sink.accept(prefix(thrown) + "track=" + track.number + " end tick=" + rel(thrown) + " reason=" + reason
            + " samples=" + track.samples + " last=" + track.last + " prev=" + track.prev
            + " blockAt=" + block + " blockAhead=" + ahead + " env=" + track.env);
    }

    private void flush(Throw thrown, Track track) {
        if (track.pending.isEmpty()) return;
        StringBuilder line = new StringBuilder(prefix(thrown));
        line.append("track=").append(track.number).append(" path from=+").append(track.pendingFrom)
            .append(" n=").append(track.pending.size()).append(" p=");
        for (int index = 0; index < track.pending.size(); index++) {
            if (index > 0) line.append(';');
            line.append(track.pending.get(index));
        }
        sink.accept(line.toString());
        track.pending.clear();
    }

    private String env(World world, Vec center, int excludeId) {
        List<Body> near = new ArrayList<>(world.near(center, ENV_RADIUS));
        near.removeIf(body -> body.id() == excludeId);
        near.sort(Comparator.comparingDouble(body -> body.pos().distanceTo(center)));
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < Math.min(ENV_COUNT, near.size()); index++) {
            Body body = near.get(index);
            if (index > 0) text.append("; ");
            text.append(body.type()).append('#').append(body.id());
            if (!body.item().isEmpty()) text.append('(').append(body.item()).append(')');
            text.append('@').append(body.pos()).append(' ').append(size(body));
            if (!body.name().isEmpty()) text.append(" \"").append(quote(body.name())).append('"');
        }
        return text.append(']').toString();
    }

    private String prefix(Throw thrown) { return "throw=" + thrown.number + " "; }

    private String rel(Throw thrown) { return "+" + (now - thrown.useTick); }

    private static String size(Body body) { return fixed(body.width(), 3) + "x" + fixed(body.height(), 3); }

    private static String flag(boolean value) { return value ? "1" : "0"; }

    private static String quote(String text) {
        return text == null ? "" : text.replace('"', '\'').replace('\n', ' ');
    }

    static String fixed(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
