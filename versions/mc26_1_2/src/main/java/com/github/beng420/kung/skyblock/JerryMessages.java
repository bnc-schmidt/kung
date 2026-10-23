package com.github.beng420.kung.skyblock;

import com.github.beng420.kung.message.KungMessages;
import com.github.beng420.kung.skyblock.JerryTimeline.Slot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** The chat lines about Jerry's rotation: hovering a mayor lists all of its terms. */
final class JerryMessages {
    /** The mayor copied now. */
    static final int ACTIVE = 0x55FF55;
    static final int OPEN = 0xFFFFFF;
    /** Plain grey text. */
    static final int PAST = 0xAAAAAA;
    /** In the hover: a term that is over. */
    static final int ENDED = 0x555555;
    static final int RUNNING = 0x55FF55;
    /** The count of known slots while it is below 6/6. */
    static final int PARTIAL = 0xFFFF55;
    private static final Pattern KNOWN = Pattern.compile("(\\d)/6");
    private static final String MORE_INFO = " for more information about the mayor rotation.";

    /** What a !jerry reply starts with while Jerry is in office (after the "[Kung] " the chat sender adds). */
    static final String ROTATION_MARKER = "[Kung] Jerry rotation: ";
    private static final Pattern MAYOR_NAME = Pattern.compile("\\b(Aatrox|Cole|Diana|Finnegan|Marina|Paul)\\b");

    private JerryMessages() { }

    private static MutableComponent line() {
        return KungMessages.component(KungMessages.Type.INFO, "Jerry", "").copy();
    }

    private static MutableComponent text(String text, int color) {
        return Component.literal(text).withColor(color);
    }

    /** The mayor's name; with its terms in the hover. */
    static MutableComponent mayor(String mayor, int color, List<Slot> slots, long nowMillis, ZoneId zone) {
        MutableComponent name = text(mayor == null ? "?" : mayor, mayor == null ? PAST : color);
        List<Slot> terms = mayor == null ? List.of() : JerryTimeline.termsOf(slots, mayor);
        if (!terms.isEmpty()) {
            name.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover(mayor, terms, nowMillis, zone))));
        }
        return name;
    }

    /**
     * One line per term: over ones dark grey, open ones white, the one running now green. Each says how far away it
     * is; with Shift held it gives the weekday, date and time in your time zone instead.
     */
    static Component hover(String mayor, List<Slot> terms, long nowMillis, ZoneId zone) {
        String zoneName = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            .format(DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH));
        MutableComponent hover = ShiftText.of(mayor + "'s terms", mayor + "'s terms (" + zoneName + ")")
            .withColor(OPEN).withStyle(ChatFormatting.BOLD);
        for (Slot term : terms) {
            boolean over = term.endMillis() <= nowMillis;
            boolean running = term.contains(nowMillis);
            String relative = over ? "ended " + relative(nowMillis - term.endMillis()) + " ago"
                : running ? "now, ends in " + relative(term.endMillis() - nowMillis)
                : "in " + relative(term.startMillis() - nowMillis);
            hover.append(Component.literal("\n"))
                .append(ShiftText.of(relative, JerryTimeline.span(term, zone)).withColor(over ? ENDED : running ? RUNNING : OPEN));
        }
        return hover.append(Component.literal("\n"))
            .append(ShiftText.of("Hold Shift for exact times", "Release Shift for relative times")
                .withColor(PAST).withStyle(ChatFormatting.ITALIC));
    }

    /** "2d 3h", "5h 15m", "40m": the two largest units. */
    static String relative(long millis) {
        long minutes = Math.max(millis, 0) / 60_000L;
        long days = minutes / 1_440L;
        long hours = minutes % 1_440L / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes % 60L + "m";
        return minutes == 0 ? "<1m" : minutes + "m";
    }

    /** "Mayor changed: A > B", with the hint about /kung jerry (a click runs it). */
    static Component switched(String from, String to, List<Slot> slots, long nowMillis, ZoneId zone) {
        MutableComponent hint = text("/kung jerry", 0x55FFFF).withStyle(style -> style
            .withClickEvent(new ClickEvent.RunCommand("/kung jerry"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Show Jerry's mayor rotation"))));
        return line().append(text("Mayor changed: ", PAST))
            .append(mayor(from, PAST, slots, nowMillis, zone))
            .append(text(" > ", PAST))
            .append(mayor(to, ACTIVE, slots, nowMillis, zone))
            .append(text(". Do ", PAST)).append(hint).append(text(MORE_INFO, PAST));
    }

    /**
     * The plain-text reply to !jerry: "Jerry rotation: Diana (switches in 4h 2m) > Paul > Cole". It has to survive as
     * chat text for other players; those who run Kung get the mayors hoverable (see {@link #decorateRotation}).
     */
    static String rotationText(List<Slot> slots, long nowMillis) {
        int index = JerryTimeline.indexAt(slots, nowMillis);
        if (index < 0) return "Jerry is in office, but I do not know his rotation yet.";
        List<String> next = JerryTimeline.rotation(slots, index);
        StringBuilder text = new StringBuilder(ROTATION_MARKER.substring("[Kung] ".length()));
        for (int position = 0; position < next.size(); position++) {
            if (position > 0) text.append(" > ");
            text.append(next.get(position) == null ? "?" : next.get(position));
            if (position == 0) {
                text.append(index == slots.size() - 1 ? " (Jerry leaves in " : " (switches in ")
                    .append(JerryPerkpocalypse.duration(slots.get(index).endMillis() - nowMillis)).append(")");
            }
        }
        return text.toString();
    }

    /**
     * An incoming reply of another Kung user's !jerry, with the mayors made hoverable from our own schedule. Anything
     * else, and a reply while we know nothing, comes back unchanged. Only the text after the marker is touched, so a
     * player named Paul is not.
     */
    static Component decorateRotation(Component message, List<Slot> slots, long nowMillis, ZoneId zone) {
        int marker = message.getString().indexOf(ROTATION_MARKER);
        if (marker < 0 || slots.isEmpty()) return message;
        int tail = marker + ROTATION_MARKER.length();
        MutableComponent result = Component.empty();
        int[] seen = {0};
        message.visit((style, text) -> {
            int from = 0;
            var matcher = MAYOR_NAME.matcher(text);
            while (matcher.find()) {
                List<Slot> terms = JerryTimeline.termsOf(slots, matcher.group());
                if (seen[0] + matcher.start() < tail || terms.isEmpty()) continue;
                result.append(Component.literal(text.substring(from, matcher.start())).withStyle(style));
                result.append(Component.literal(matcher.group()).withStyle(style.withHoverEvent(
                    new HoverEvent.ShowText(hover(matcher.group(), terms, nowMillis, zone)))));
                from = matcher.end();
            }
            result.append(Component.literal(text.substring(from)).withStyle(style));
            seen[0] += text.length();
            return java.util.Optional.<Void>empty();
        }, Style.EMPTY);
        return result;
    }

    /** The lines of /kung jerry. */
    static List<Component> status(boolean inOffice, String otherMayor, long electionYear, List<Slot> slots,
                                  long nowMillis, String sync, ZoneId zone) {
        List<Component> lines = new ArrayList<>();
        if (!inOffice) {
            lines.add(line().append(text("Jerry is not in office (mayor: " + (otherMayor.isBlank() ? "unknown" : otherMayor) + ").", PAST)));
            return lines;
        }
        lines.add(line().append(text("Jerry is in office (election year " + electionYear + ").", PAST)));
        int index = JerryTimeline.indexAt(slots, nowMillis);
        Slot current = index < 0 ? null : slots.get(index);
        MutableComponent copying = line().append(text("Copying now: ", PAST));
        if (current == null || current.mayor() == null) {
            copying.append(text("unknown, click Jerry in the Hub.", PAST));
        } else {
            boolean last = index == slots.size() - 1;
            copying.append(mayor(current.mayor(), ACTIVE, slots, nowMillis, zone))
                .append(text((last ? ", until Jerry leaves office in " : ", switches in ")
                    + JerryPerkpocalypse.duration(current.endMillis() - nowMillis) + ".", PAST));
        }
        lines.add(copying);
        MutableComponent rotation = line().append(text("Rotation: ", PAST));
        if (index < 0) {
            rotation.append(text("unknown yet.", PAST));
        } else {
            List<String> next = JerryTimeline.rotation(slots, index);
            for (int position = 0; position < next.size(); position++) {
                if (position > 0) rotation.append(text(" > ", PAST));
                rotation.append(mayor(next.get(position), position == 0 ? ACTIVE : OPEN, slots, nowMillis, zone));
            }
        }
        lines.add(rotation);
        lines.add(serverLine(sync));
        return lines;
    }

    /** "Server: ok, the server knows 6/6 slots.": the 6/6 is green once all slots are known, yellow before. */
    private static MutableComponent serverLine(String sync) {
        MutableComponent line = line().append(text("Server: ", PAST));
        var known = KNOWN.matcher(sync);
        if (known.find()) {
            line.append(text(sync.substring(0, known.start()), PAST))
                .append(text(known.group(), known.group(1).equals("6") ? RUNNING : PARTIAL))
                .append(text(sync.substring(known.end()) + ".", PAST));
        } else {
            line.append(text(sync + ".", PAST));
        }
        return line;
    }
}
