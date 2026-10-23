package com.github.beng420.kung.feature.dungeon;

import java.util.regex.Pattern;

/**
 * The Quiz puzzle (Oruo the Omniscient) as seen in chat: each "Question #N" starts a countdown, an answer or the end
 * of the quiz stops it, and a countdown that runs out asks for one reminder, then more if repeating.
 */
final class QuizReminderState {
    enum Line { QUESTION, ANSWERED, ENDED, OTHER }

    enum Action { NONE, FIRST, REPEAT }

    private static final Pattern QUESTION = Pattern.compile("^Question #\\d+$");
    // "Beng114 answered Question #1 correctly!", "marps answered the final question correctly!"
    private static final Pattern ANSWERED = Pattern.compile(
        "^\\[STATUE] Oruo the Omniscient: .+ answered (?:Question #\\d+|the final question) correctly!$");
    private static final Pattern ENDED = Pattern.compile(
        "^\\[STATUE] Oruo the Omniscient: (?:I bestow upon you all the power of a hundred years!|Yikes.*)$");

    private long dueMillis = -1;
    private boolean remindedOnce;

    static Line classify(String line) {
        String text = line == null ? "" : line.strip();
        if (QUESTION.matcher(text).matches()) return Line.QUESTION;
        if (ANSWERED.matcher(text).matches()) return Line.ANSWERED;
        if (ENDED.matcher(text).matches()) return Line.ENDED;
        return Line.OTHER;
    }

    /** A chat line: a question starts the countdown over, an answer or the end stops it. */
    void observe(String line, long nowMillis, long delayMillis) {
        switch (classify(line)) {
            case QUESTION -> {
                dueMillis = nowMillis + delayMillis;
                remindedOnce = false;
            }
            case ANSWERED, ENDED -> dueMillis = -1;
            case OTHER -> { }
        }
    }

    /** FIRST for the first reminder of a question, REPEAT for later ones, NONE while nothing is due. */
    Action tick(long nowMillis, boolean repeat, long repeatMillis) {
        if (dueMillis < 0 || nowMillis < dueMillis) return Action.NONE;
        Action action = remindedOnce ? Action.REPEAT : Action.FIRST;
        remindedOnce = true;
        dueMillis = repeat ? nowMillis + repeatMillis : -1;
        return action;
    }

    void reset() {
        dueMillis = -1;
        remindedOnce = false;
    }
}
