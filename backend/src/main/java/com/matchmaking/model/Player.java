package com.matchmaking.model;

public class Player {
    public final long id;
    public final String name;
    public final int mmr;
    public final boolean bot;

    public Player(long id, String name, int mmr, boolean bot) {
        this.id = id;
        this.name = name;
        this.mmr = mmr;
        this.bot = bot;
    }

    public String rankLabel() {
        return Rank.fromMmr(mmr).label;
    }
}
