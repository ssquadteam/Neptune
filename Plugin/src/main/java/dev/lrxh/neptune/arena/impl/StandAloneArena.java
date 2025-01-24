package dev.lrxh.neptune.arena.impl;

import dev.lrxh.neptune.arena.Arena;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;


@Getter
@Setter
public class StandAloneArena extends Arena {
    private Location min;
    private Location max;
    private double deathY;
    private double limit;
    private boolean used;

    public StandAloneArena(String name, String displayName, Location redSpawn, Location blueSpawn, Location min, Location max, double deathY, double limit, boolean enabled) {
        super(name, displayName, redSpawn, blueSpawn, enabled);
        this.min = min;
        this.max = max;
        this.limit = limit;
        this.deathY = deathY;
        this.used = false;
    }

    public StandAloneArena(String arenaName) {
        super(arenaName, arenaName, null, null, false);
        this.min = null;
        this.max = null;
        this.limit = 68321;
        this.deathY = 0;
        this.used = false;
    }

    @Override
    public boolean isSetup() {
        return !(getRedSpawn() == null || getBlueSpawn() == null || min == null || max == null);
    }

}