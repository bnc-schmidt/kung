package com.github.beng420.kung.feature.garden;

import com.github.beng420.kung.skyblock.HypixelLocation;
import java.util.List;
import java.util.regex.Pattern;

/** Kernel currency is independent of Feast donation tiers and survives event changes. */
final class FeastKernels {
    private static final Pattern BALANCE = Pattern.compile("^Your Kernels: ([0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)$");
    private static final Pattern SIDEBAR_BALANCE = Pattern.compile(
        "^Kernels: ([0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?: \\([+-][0-9,]+\\))?$");
    private static final Pattern SIDEBAR_SUFFIX_BALANCE = Pattern.compile(
        "^([0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?: \\([+-][0-9,]+\\))? Kernels$");
    private static final Pattern GAIN = Pattern.compile(
        "^(?:\\[\\d{2}:\\d{2}:\\d{2}\\] )?\\[NPC\\] Feast Chef Ted: "
            + "Thanks for the donation! I've added a Kernel to your purse\\.$");

    private Long balance;
    private Object menu;
    private Long lastMenuBalance;
    private Long lastSidebarBalance;
    private long pendingGains;
    private final FeastMilestoneClaims claims = new FeastMilestoneClaims();

    Long balance() { return balance; }
    long pendingGains() { return pendingGains; }
    Long sidebarBalance() { return lastSidebarBalance; }

    void restore(Long saved, long savedPendingGains) {
        if (balance == null && saved != null && saved >= 0) {
            balance = saved;
            pendingGains = Math.clamp(savedPendingGains, 0L, saved);
        }
    }

    static boolean supportsMenu(String title) {
        String clean = HypixelLocation.clean(title);
        return clean.equals("Grand Feast") || clean.equals("Grand Bakery");
    }

    static Long readMenu(String title, List<FeastProgress.MenuItem> items) {
        if (!supportsMenu(title) || items.size() > 54) return null;
        Long found = null;
        for (var item : items) {
            for (String raw : item.lore().stream().limit(40).toList()) {
                var matcher = BALANCE.matcher(FeastProgress.cleanMenuText(raw));
                if (!matcher.matches()) continue;
                try {
                    long amount = Long.parseLong(matcher.group(1).replace(",", ""));
                    if (found != null && found != amount) return null;
                    found = amount;
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return found;
    }

    boolean observeMenu(Object currentMenu, Long amount) {
        if (menu != currentMenu) {
            menu = currentMenu;
            lastMenuBalance = null;
        }
        if (amount == null || amount < 0 || amount.equals(lastMenuBalance)) return false;
        lastMenuBalance = amount;
        // A fresh owned total settles outstanding claims, including spending at Scott.
        claims.clearPending();
        boolean changed = !amount.equals(balance);
        balance = amount;
        pendingGains = 0;
        return changed;
    }

    boolean observeMessage(String text) {
        if (balance == null || balance == Long.MAX_VALUE || !donationMessage(text)) return false;
        balance++;
        pendingGains++;
        claims.earned(1);
        return true;
    }

    boolean beginMilestoneClaim(FeastMilestoneClaims.Milestone milestone, long now) {
        return claims.begin(milestone, balance, now);
    }

    boolean pendingMilestoneClaims() { return claims.pending(); }

    FeastMilestoneClaims.Credit observeMilestone(FeastMilestoneClaims.Milestone milestone, long now) {
        var credit = claims.observeServer(milestone, now);
        if (credit != null && balance != null && credit.minimumBalance() != null && credit.minimumBalance() > balance) {
            long gained = credit.minimumBalance() - balance;
            balance += gained;
            pendingGains += gained;
        }
        return credit;
    }

    void selectFeast(FeastContext.Event event) {
        if (event != null) claims.selectEvent(event.key());
    }

    static boolean donationMessage(String text) {
        return GAIN.matcher(HypixelLocation.clean(text)).matches();
    }

    boolean observeMessage(String text, List<String> sidebarBeforeMessage) {
        if (!donationMessage(text)) return false;
        // Shared sidebar publication waits until end-of-tick; the applied scoreboard may already
        // contain the pre-donation balance when Ted confirms the next Kernel in the same packet batch.
        observeSidebar(sidebarBeforeMessage);
        return observeMessage(text);
    }

    boolean observeSidebar(List<String> lines) {
        Long found = null;
        for (String raw : lines) {
            String clean = FeastProgress.cleanMenuText(raw);
            var matcher = SIDEBAR_BALANCE.matcher(clean);
            if (!matcher.matches()) matcher = SIDEBAR_SUFFIX_BALANCE.matcher(clean);
            if (!matcher.matches()) continue;
            try {
                long amount = Long.parseLong(matcher.group(1).replace(",", ""));
                if (found != null && found != amount) return false;
                found = amount;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (found == null || found.equals(lastSidebarBalance)) return false;
        lastSidebarBalance = found;
        // Sidebar refreshes can trail gains. Protect only Ted confirmations and server-confirmed claims;
        // larger decreases are corrections/spending, and a fresh menu always supplies the total.
        if (balance != null && found < balance && balance - found <= pendingGains) {
            pendingGains = balance - found;
            return false;
        }
        boolean changed = !found.equals(balance);
        balance = found;
        pendingGains = 0;
        return changed;
    }

    void worldChanged() {
        lastSidebarBalance = null;
        claims.clearPending();
    }

    void closeMenu() {
        menu = null;
        lastMenuBalance = null;
    }

    void invalidate() {
        balance = null;
        pendingGains = 0;
        claims.reset();
        // Unchanged lore from a menu left open across a profile change must not restore its balance.
    }

    void reset() {
        invalidate();
        closeMenu();
        worldChanged();
    }
}
