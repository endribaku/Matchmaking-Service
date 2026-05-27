package com.matchmaking.service;

import com.matchmaking.model.Player;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe registry of all players (humans and bots).
 *
 * Concurrency notes:
 *  - ConcurrentHashMap lets multiple HTTP worker threads read and write
 *    without locking the whole map.
 *  - AtomicLong gives lock-free unique IDs (no synchronized counter).
 */
public class PlayerService {

    private final ConcurrentHashMap<Long, Player> players = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Random random = new Random();

    private static final String[] BOT_NAMES = {
        "s1mple", "ZywOo", "NiKo", "device", "sh1ro", "Twistzz", "ropz", "stavn",
        "Magisk", "blameF", "FalleN", "coldzera", "olofmeister", "GeT_RiGhT",
        "f0rest", "kennyS", "shox", "byali", "TaZ", "Snax", "dev1ce", "Xyp9x"
    };

    public Player register(String name) {
        long id = nextId.getAndIncrement();
        int mmr = randomStartingMmr();
        Player p = new Player(id, name, mmr, false);
        players.put(id, p);
        return p;
    }

    public Player createBot() {
        return createBot(randomStartingMmr());
    }

    /**
     * Spawn a bot with MMR near a target value (±250). Used by the demo
     * "spawn bots" button so that filler bots are skill-close to the
     * requesting player and a match actually forms quickly.
     */
    public Player createBotNear(int targetMmr) {
        int mmr = Math.max(0, targetMmr - 250 + random.nextInt(501));
        return createBot(mmr);
    }

    private Player createBot(int mmr) {
        long id = nextId.getAndIncrement();
        String name = BOT_NAMES[random.nextInt(BOT_NAMES.length)] + "_" + id;
        Player p = new Player(id, name, mmr, true);
        players.put(id, p);
        return p;
    }

    public Player get(long id) {
        return players.get(id);
    }

    private int randomStartingMmr() {
        // 800..2599 — covers Silver through Global, good spread for demo.
        return 800 + random.nextInt(1800);
    }
}
