package com.matchmaking.model;

import java.util.List;

public class Match {
    public final long id;
    public final List<Player> teamA;
    public final List<Player> teamB;
    public final long createdAtMs;

    public Match(long id, List<Player> teamA, List<Player> teamB) {
        this.id = id;
        this.teamA = teamA;
        this.teamB = teamB;
        this.createdAtMs = System.currentTimeMillis();
    }

    public int teamAMmr() { return teamA.stream().mapToInt(p -> p.mmr).sum(); }
    public int teamBMmr() { return teamB.stream().mapToInt(p -> p.mmr).sum(); }
}
