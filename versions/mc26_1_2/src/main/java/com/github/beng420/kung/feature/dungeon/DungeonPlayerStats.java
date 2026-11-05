package com.github.beng420.kung.feature.dungeon;

import java.util.UUID;
import java.util.EnumSet;

public final class DungeonPlayerStats {
    private final UUID uuid;
    private String name;
    private DungeonRunStats.DungeonClass dungeonClass = DungeonRunStats.DungeonClass.UNKNOWN;
    private int deaths;
    private int roomsCleared;
    private int soloRoomsCleared;
    private final DungeonSecretCounter secrets = new DungeonSecretCounter();
    private int totalSecretsFound = -1;
    private int roomGridX = -1;
    private int roomGridZ = -1;
    private long lastSeenTick;
    private final EnumSet<DungeonBonusContribution> bonuses = EnumSet.noneOf(DungeonBonusContribution.class);

    DungeonPlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public DungeonRunStats.DungeonClass dungeonClass() { return dungeonClass; }
    public int deaths() { return deaths; }
    public int roomsCleared() { return roomsCleared; }
    public int soloRoomsCleared() { return soloRoomsCleared; }
    public int secretsFound() { return secrets.value(); }
    public boolean hasSecretsFound() { return secrets.known(); }
    public String secretsSource() { return secrets.source().name(); }
    public boolean hasOwnRunSecrets() {
        return secrets.source() == DungeonSecretCounter.Source.PERSONAL || secrets.source() == DungeonSecretCounter.Source.API_DELTA;
    }
    public int totalSecretsFound() { return totalSecretsFound; }
    public int roomGridX() { return roomGridX; }
    public int roomGridZ() { return roomGridZ; }
    public long lastSeenTick() { return lastSeenTick; }
    public String bonusMarkers() {
        StringBuilder result = new StringBuilder();
        for (DungeonBonusContribution bonus : bonuses) result.append(bonus.marker());
        return result.toString();
    }

    void setName(String name) { this.name = name; }
    void setDungeonClass(DungeonRunStats.DungeonClass dungeonClass) { this.dungeonClass = dungeonClass; }
    void incrementDeaths(int count) { deaths += count; }
    void setDeaths(int deaths) { this.deaths = Math.max(this.deaths, deaths); }
    void incrementSecrets(int count) { secrets.incrementObserved(count); }
    void observeRoom(int roomGridX, int roomGridZ, long nowTick) {
        this.roomGridX = roomGridX;
        this.roomGridZ = roomGridZ;
        this.lastSeenTick = nowTick;
    }
    void setRoomClearBounds(int minimum, int maximum) {
        soloRoomsCleared = Math.max(0, minimum);
        roomsCleared = Math.max(soloRoomsCleared, maximum);
    }
    void addBonus(DungeonBonusContribution bonus) { bonuses.add(bonus); }
    /** An explicit personal measurement, never a shared tab or room counter. */
    void setSecretsFound(int secretsFound) { secrets.personal(secretsFound); }
    /** Only the player's own known counter may be accepted as a remote self-report. */
    void setSyncedSecretsFound(int secretsFound, long reportedAtMillis) {
        secrets.selfReport(secretsFound, reportedAtMillis);
    }
    void setTotalSecretsFound(int totalSecretsFound) {
        this.totalSecretsFound = Math.max(this.totalSecretsFound, totalSecretsFound);
    }
    void setApiRunSecretsFound(int apiRunSecretsFound) {
        secrets.apiDelta(apiRunSecretsFound);
    }

    void resetRunCounters() {
        deaths = 0;
        roomsCleared = 0;
        soloRoomsCleared = 0;
        secrets.reset();
        roomGridX = -1;
        roomGridZ = -1;
        lastSeenTick = 0;
        bonuses.clear();
    }

    void merge(DungeonPlayerStats other) {
        bonuses.addAll(other.bonuses);
        // These rows are aliases of the same player, not disjoint contributions.
        deaths = Math.max(deaths, other.deaths);
        roomsCleared = Math.max(roomsCleared, other.roomsCleared);
        soloRoomsCleared = Math.max(soloRoomsCleared, other.soloRoomsCleared);
        secrets.merge(other.secrets);
        totalSecretsFound = Math.max(totalSecretsFound, other.totalSecretsFound);
        if (dungeonClass == DungeonRunStats.DungeonClass.UNKNOWN
            && other.dungeonClass != DungeonRunStats.DungeonClass.UNKNOWN) {
            dungeonClass = other.dungeonClass;
        }
        if (other.roomGridX >= 0 && (roomGridX < 0 || other.lastSeenTick > lastSeenTick)) {
            roomGridX = other.roomGridX;
            roomGridZ = other.roomGridZ;
            lastSeenTick = other.lastSeenTick;
        }
    }
}
