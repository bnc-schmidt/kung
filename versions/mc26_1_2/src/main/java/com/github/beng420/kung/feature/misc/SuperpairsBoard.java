package com.github.beng420.kung.feature.misc;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Remembers revealed fields without mistaking the server's changing button prompts for cards. */
public final class SuperpairsBoard {
    private static final Pattern ENCHANTMENT = Pattern.compile("[A-Za-z][A-Za-z '\\-]* [IVXLCDM]+");
    private static final Pattern ENCHANTING_XP = Pattern.compile("[\\d,.]+[kKmM]? Enchanting Exp(?: x\\d+)?");
    private final Set<Integer> boardSlots = new HashSet<>();
    private final Map<Integer, Card> rewards = new LinkedHashMap<>();
    private final Set<Integer> bonuses = new HashSet<>();
    /** The kinds of card seen, numbered in the order they first showed up; a kind keeps its number. */
    private final Map<String, Integer> legend = new LinkedHashMap<>();
    private long revision;
    private long cachedRevision = -1;
    private List<CardCount> cachedCards = List.of();
    private PairStats cachedSummary = new PairStats(0, 0, 0, 0);

    public enum Kind { IGNORED, MARKER, REWARD, BONUS }

    /** The supported 54-slot menu has a seven-by-four playing area inside its border. */
    public static boolean isBoardSlot(int topSlotCount, int slot) {
        if (topSlotCount != 54 || slot < 0 || slot >= topSlotCount) {
            return false;
        }
        int row = slot / 9;
        int column = slot % 9;
        return row >= 1 && row <= 4 && column >= 1 && column <= 7;
    }

    public static Kind classify(String itemId, String label) {
        String item = itemPath(itemId);
        if (item.isEmpty() || item.equals("air")) {
            return Kind.IGNORED;
        }
        // These names change on every move, including when a real bonus is selected elsewhere.
        if (item.endsWith("_stained_glass")) {
            return Kind.MARKER;
        }
        if (item.contains("glass_pane") || item.equals("barrier") || item.equals("clock")
            || item.equals("bookshelf")) {
            return Kind.IGNORED;
        }
        String name = HypixelLocation.clean(label).toLowerCase(Locale.ROOT);
        if (name.isBlank()) {
            return Kind.IGNORED;
        }
        String prompt = name.endsWith("!") ? name.substring(0, name.length() - 1) : name;
        if (prompt.equals("?") || SuperpairsPlanner.Prompt.of(label) != SuperpairsPlanner.Prompt.UNKNOWN) {
            return Kind.MARKER;
        }
        if (item.equals("diamond") || prompt.equals("instant find") || prompt.equals("extra clicks")
            || prompt.equals("extra click") || prompt.equals("free click") || prompt.equals("free clicks")
            || prompt.equals("bonus click") || prompt.equals("bonus clicks")
            || prompt.matches("gained \\+[1-3] clicks?")) {
            return Kind.BONUS;
        }
        if (name.contains("superpairs") || name.contains("remaining") || name.contains("timer")) {
            return Kind.IGNORED;
        }
        return Kind.REWARD;
    }

    /** Empty/hidden replacements never erase a remembered reveal. Observing a slot twice is idempotent. */
    public Kind observe(int topSlotCount, int slot, String itemId, String label, int count) {
        if (!isBoardSlot(topSlotCount, slot) || count <= 0) {
            return Kind.IGNORED;
        }
        Kind kind = classify(itemId, label);
        if (kind == Kind.IGNORED) {
            return kind;
        }
        boolean changed = boardSlots.add(slot);
        if (kind == Kind.REWARD) {
            Card card = card(itemId, label, count);
            legend.putIfAbsent(card.key(), legend.size());
            changed |= !card.equals(rewards.put(slot, card));
            changed |= bonuses.remove(slot);
        } else if (kind == Kind.BONUS) {
            changed |= bonuses.add(slot);
            changed |= rewards.remove(slot) != null;
        }
        if (changed) {
            revision++;
        }
        return kind;
    }

    /** The label of the card seen in a slot (with " xN" for stacks), or null. */
    public String labelAt(int slot) {
        Card card = rewards.get(slot);
        return card == null ? null : card.label();
    }

    /** The slots of each kind of card seen, in the order the kinds were first seen. */
    public Map<String, List<Integer>> slotsByKind() {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (String key : legend.keySet()) {
            groups.put(key, new java.util.ArrayList<>());
        }
        rewards.forEach((slot, card) -> groups.get(card.key()).add(slot));
        groups.values().forEach(java.util.Collections::sort);
        groups.values().removeIf(List::isEmpty);
        return groups;
    }

    /** 0, 1, 2 ... for the kind of card in a slot in the order the kinds were first seen, or -1. */
    public int legendIndexAt(int slot) {
        Card card = rewards.get(slot);
        Integer index = card == null ? null : legend.get(card.key());
        return index == null ? -1 : index;
    }

    /** The label of a kind of card from {@link #slotsByKind()}. */
    public String labelOfKind(String key) {
        for (Card card : rewards.values()) {
            if (card.key().equals(key)) return card.label();
        }
        return null;
    }

    /** Playable slots no card has been seen in yet, in slot order. */
    public List<Integer> unseenSlots() {
        return boardSlots.stream().filter(slot -> !rewards.containsKey(slot) && !bonuses.contains(slot)).sorted().toList();
    }

    public int boardSlotCount() {
        return boardSlots.size();
    }

    public int bonusSlotCount() {
        return bonuses.size();
    }

    public long revision() {
        return revision;
    }

    public List<CardCount> cards() {
        refreshSummary();
        return cachedCards;
    }

    public PairStats summary() {
        refreshSummary();
        return cachedSummary;
    }

    public void reset() {
        boardSlots.clear();
        rewards.clear();
        bonuses.clear();
        legend.clear();
        revision++;
    }

    private void refreshSummary() {
        if (cachedRevision == revision) {
            return;
        }
        Map<String, CardCount> counts = new LinkedHashMap<>();
        for (Card card : rewards.values()) {
            CardCount previous = counts.get(card.key());
            counts.put(card.key(), new CardCount(card.key(), card.label(), previous == null ? 1 : previous.count() + 1));
        }
        cachedCards = counts.values().stream()
            .sorted(Comparator.comparingInt((CardCount card) -> card.enchantment() ? 0 : 1)
                .thenComparingInt(card -> card.complete() ? 1 : 0)
                .thenComparing(CardCount::label, String.CASE_INSENSITIVE_ORDER))
            .toList();
        int pairs = 0;
        int singles = 0;
        for (CardCount card : cachedCards) {
            pairs += card.pairs();
            singles += card.singles();
        }
        int unknown = Math.max(0, boardSlots.size() - rewards.size() - bonuses.size());
        int maximumPairs = Math.max(0, (boardSlots.size() - bonuses.size()) / 2);
        cachedSummary = new PairStats(pairs, singles, unknown, maximumPairs);
        cachedRevision = revision;
    }

    private static Card card(String itemId, String label, int count) {
        String name = HypixelLocation.clean(label);
        String amount = count > 1 ? " x" + count : "";
        return new Card(itemPath(itemId) + "|" + name.toLowerCase(Locale.ROOT) + "|" + count, name + amount);
    }

    private static String itemPath(String itemId) {
        if (itemId == null) {
            return "";
        }
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        return colon < 0 ? normalized : normalized.substring(colon + 1);
    }

    static String rewardLabel(String itemId, String displayName, List<String> lore) {
        String name = HypixelLocation.clean(displayName);
        // Hypixel also renders enchanted books as swords, bows and other equipment.
        if (itemPath(itemId).equals("enchanted_book") || name.equalsIgnoreCase("Enchanted Book")) {
            for (String line : lore) {
                String enchantment = HypixelLocation.clean(line);
                if (ENCHANTMENT.matcher(enchantment).matches()) return enchantment;
            }
        }
        return name;
    }

    private record Card(String key, String label) { }

    public record CardCount(String key, String label, int count) {
        public int pairs() { return count / 2; }
        public int singles() { return count % 2; }
        public boolean complete() { return count > 0 && singles() == 0; }
        public boolean enchantment() {
            return key.startsWith("enchanted_book|") || label.equalsIgnoreCase("Enchanted Book")
                || ENCHANTMENT.matcher(label).matches();
        }
        public boolean enchantingXp() { return ENCHANTING_XP.matcher(label).matches(); }
    }

    /** Known pairs are identities seen twice, not a claim that Hypixel awarded their rewards. */
    public record PairStats(int knownPairs, int singleCards, int unknownFields, int maximumTotalPairs) {
        /** Each known single needs one hidden partner; remaining fields may still be bonuses. */
        public int maximumUnseenPairs() { return Math.max(0, (unknownFields - singleCards) / 2); }
    }
}
