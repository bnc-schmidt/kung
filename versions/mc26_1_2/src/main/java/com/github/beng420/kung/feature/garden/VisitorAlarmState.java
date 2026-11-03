package com.github.beng420.kung.feature.garden;

/**
 * Client-thread state. Rings while the visitor queue is full (5, up to 10 with Max Occupancy), until the player
 * serves a visitor or mutes it. There is no hidden extra slot or timer: a full queue is the whole trigger.
 */
final class VisitorAlarmState {
    private long nextReminder = -1;
    private VisitorQueue.Snapshot queue;
    private boolean ringing;
    private boolean acknowledged;

    void observe(VisitorQueue.Snapshot next) {
        if (next == null) return;
        boolean departed = queue != null && !next.visitors().containsAll(queue.visitors());
        if (!next.full()) {
            // Room again: the next fill rings.
            ringing = false;
            acknowledged = false;
            nextReminder = -1;
        } else if (departed) {
            // A visitor was served and replaced within one update: stay quiet until room opens up.
            ringing = false;
            acknowledged = true;
            nextReminder = -1;
        }
        queue = next;
    }

    /** Called once the roster has been stable: latches the alarm while the queue is full. */
    void tick() {
        if (!acknowledged && queue != null && queue.full()) ringing = true;
    }

    boolean acknowledge() {
        if (!ringing) return false;
        ringing = false;
        nextReminder = -1;
        // The click acknowledges this fill before the server removes/replaces the visitor.
        acknowledged = true;
        return true;
    }

    boolean reminderDue(long now) {
        if (!ringing || (nextReminder != -1 && now < nextReminder)) return false;
        // Schedule from delivery time so a paused client does not flood chat on resume.
        nextReminder = now + 10_000;
        return true;
    }

    boolean ringing() { return ringing; }
    VisitorQueue.Snapshot queue() { return queue; }

    void reset() {
        nextReminder = -1;
        queue = null;
        ringing = false;
        acknowledged = false;
    }
}
