package com.matchmaking.model;

public class QueueTicket {
    public final Player player;
    public final long joinedAtMs;

    public QueueTicket(Player player) {
        this.player = player;
        this.joinedAtMs = System.currentTimeMillis();
    }

    public long waitMs() {
        return System.currentTimeMillis() - joinedAtMs;
    }
}
