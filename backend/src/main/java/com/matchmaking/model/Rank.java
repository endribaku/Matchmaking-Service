package com.matchmaking.model;

public enum Rank {
    SILVER(0,    "Silver"),
    GOLD(1000,   "Gold Nova"),
    MG(1500,     "Master Guardian"),
    DMG(2000,    "Distinguished MG"),
    GLOBAL(2500, "Global Elite");

    public final int minMmr;
    public final String label;

    Rank(int minMmr, String label) {
        this.minMmr = minMmr;
        this.label = label;
    }

    public static Rank fromMmr(int mmr) {
        Rank[] ranks = values();
        for (int i = ranks.length - 1; i >= 0; i--) {
            if (mmr >= ranks[i].minMmr) return ranks[i];
        }
        return SILVER;
    }
}
