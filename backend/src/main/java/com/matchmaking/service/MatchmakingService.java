package com.matchmaking.service;

import com.matchmaking.model.Match;
import com.matchmaking.model.Player;
import com.matchmaking.model.QueueTicket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The core matchmaking engine. This is where most of the parallel
 * programming concepts live. Four independent thread pools run
 * concurrently:
 *
 *  1. HTTP workers (in ApiServer) — handle requests in parallel.
 *  2. Matchmaker tick — runs the matching loop every 500 ms.
 *  3. Bot populator — keeps the queue full of bot players so the
 *     service always has activity.
 *  4. Match setup workers — handle post-match work (server allocation
 *     etc.) in parallel without blocking the matchmaker tick.
 *
 * Concurrency primitives used here:
 *  - LinkedBlockingQueue<QueueTicket>: thread-safe producer/consumer hand-off
 *  - ScheduledExecutorService x2: matchmaker tick + bot populator
 *  - ThreadPoolExecutor: match setup pool (exposed for live stats)
 *  - ConcurrentHashMap: player→match index, match registry
 *  - AtomicLong: lock-free counters and ID generation
 *  - ReentrantLock: guards the in-memory sorted pool during a tick
 *  - volatile: cross-thread visibility for last-tick duration
 */
public class MatchmakingService {

    public static final int TEAM_SIZE = 5;
    public static final int MATCH_SIZE = TEAM_SIZE * 2;

    private static final int BASE_MMR_RANGE    = 300;
    private static final int MMR_RANGE_PER_SEC = 100;
    private static final int MAX_MMR_RANGE     = 1500;

    private static final long TICK_MS     = 500;
    private static final long POPULATE_MS = 500;

    // Queue target and refill rate scale with the setup pool size: more
    // setup workers => need a bigger backlog so they're actually all busy.
    // Each "slot" gets ~12 bots in the queue (enough for the worker to
    // chew through a few matches before refill). Refill rate is also
    // tied to slot count, otherwise high-parallelism pools drain the
    // queue faster than the populator can replenish.
    private static final int BOTS_PER_SLOT       = 12;
    private static final int MIN_TARGET_BOTS     = 20;
    private static final int BOTS_PER_TICK_PER_SLOT = 6;
    private static final int MIN_BOTS_PER_TICK   = 4;

    private final PlayerService players;

    private final LinkedBlockingQueue<QueueTicket> incoming = new LinkedBlockingQueue<>();
    private final List<QueueTicket> waitingPool = new ArrayList<>();
    private final ReentrantLock poolLock = new ReentrantLock();

    private final ConcurrentHashMap<Long, Match> matches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> playerToMatch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, QueueTicket> playerToTicket = new ConcurrentHashMap<>();

    private final AtomicLong nextMatchId          = new AtomicLong(1);
    private final AtomicLong totalMatchesCreated  = new AtomicLong(0);
    private final AtomicLong tickCount            = new AtomicLong(0);
    private final AtomicLong botsSpawnedTotal     = new AtomicLong(0);
    private volatile long lastTickDurationMs      = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "matchmaker-tick");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService botPopulator = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-populator");
        t.setDaemon(true);
        return t;
    });

    // Constructed directly (not via Executors.newFixedThreadPool) so we
    // keep a typed reference and can expose live stats.
    private final ThreadPoolExecutor matchSetupPool = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        r -> { Thread t = new Thread(r, "match-setup"); t.setDaemon(true); return t; }
    );

    public MatchmakingService(PlayerService players) {
        this.players = players;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        botPopulator.scheduleAtFixedRate(this::populate, 200, POPULATE_MS, TimeUnit.MILLISECONDS);
        System.out.println("Matchmaker started (tick every " + TICK_MS + " ms)");
        System.out.println("Bot populator started (initial target " + targetBots() + " bots, every " + POPULATE_MS + " ms)");
    }

    /**
     * Target queue size — scales with the setup pool so more workers
     * actually have more matches available to set up in parallel.
     */
    private int targetBots() {
        return Math.max(MIN_TARGET_BOTS, matchSetupPool.getMaximumPoolSize() * BOTS_PER_SLOT);
    }

    private int maxBotsPerTick() {
        return Math.max(MIN_BOTS_PER_TICK, matchSetupPool.getMaximumPoolSize() * BOTS_PER_TICK_PER_SLOT);
    }

    public boolean joinQueue(Player p) {
        if (playerToMatch.containsKey(p.id)) return false;
        if (playerToTicket.containsKey(p.id)) return false;
        QueueTicket ticket = new QueueTicket(p);
        playerToTicket.put(p.id, ticket);
        incoming.offer(ticket);
        return true;
    }

    public boolean leaveQueue(long playerId) {
        return playerToTicket.remove(playerId) != null;
    }

    public Match matchFor(long playerId) {
        Long mid = playerToMatch.get(playerId);
        if (mid == null) return null;
        return matches.get(mid);
    }

    /**
     * Release a player from their current match. Used when the user
     * clicks "Back to Lobby" — without this, the next /queue/status
     * poll would still report MATCHED and snap them back to the same
     * match screen.
     *
     * For this demo we treat a release as ending the whole match: all
     * 10 player→match entries are cleared and the match is removed
     * from the registry (otherwise activeMatches would grow forever
     * because bots never call release themselves).
     */
    public boolean releaseMatch(long playerId) {
        Long mid = playerToMatch.remove(playerId);
        if (mid == null) return false;
        Match m = matches.remove(mid);
        if (m == null) return true;
        for (Player p : m.teamA) playerToMatch.remove(p.id);
        for (Player p : m.teamB) playerToMatch.remove(p.id);
        return true;
    }

    public QueueTicket ticketFor(long playerId) {
        return playerToTicket.get(playerId);
    }

    public int waitingCount() { return playerToTicket.size(); }
    public long totalMatches() { return totalMatchesCreated.get(); }
    public int activeMatches() { return matches.size(); }
    public long tickCount() { return tickCount.get(); }
    public long lastTickMs() { return lastTickDurationMs; }
    public long botsSpawned() { return botsSpawnedTotal.get(); }
    public int setupActive() { return matchSetupPool.getActiveCount(); }
    public int setupPoolSize() { return matchSetupPool.getMaximumPoolSize(); }
    public int incomingQueueSize() { return incoming.size(); }

    /**
     * Resize the match-setup worker pool at runtime. Lets the UI demo how
     * parallelism width changes throughput: with 1 worker, matches set up
     * sequentially; with N, up to N at the same time.
     *
     * ThreadPoolExecutor requires core <= max at all times, so the order
     * of the two setters depends on whether we're growing or shrinking.
     */
    public void setSetupPoolSize(int size) {
        int n = Math.max(1, Math.min(16, size));
        if (n > matchSetupPool.getMaximumPoolSize()) {
            matchSetupPool.setMaximumPoolSize(n);
            matchSetupPool.setCorePoolSize(n);
        } else {
            matchSetupPool.setCorePoolSize(n);
            matchSetupPool.setMaximumPoolSize(n);
        }
    }

    /**
     * Bot populator — runs on its own scheduled thread, completely
     * independent of the matchmaker tick. Keeps the queue full of bot
     * players so the service always has visible activity. New bots get
     * uniformly-random MMR; matches form naturally among bot clusters.
     */
    private void populate() {
        try {
            int target = targetBots();
            int current = waitingCount();
            if (current >= target) return;
            int need = Math.min(maxBotsPerTick(), target - current);
            for (int i = 0; i < need; i++) {
                Player bot = players.createBot();
                if (joinQueue(bot)) botsSpawnedTotal.incrementAndGet();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * One tick of the matchmaker. Single-threaded by design — the
     * parallelism is around it (producers feeding the queue, setup
     * workers consuming finished matches), not inside it.
     */
    private void tick() {
        long startNs = System.nanoTime();
        poolLock.lock();
        try {
            QueueTicket t;
            while ((t = incoming.poll()) != null) {
                if (playerToTicket.containsKey(t.player.id)) {
                    waitingPool.add(t);
                }
            }
            waitingPool.removeIf(tk -> !playerToTicket.containsKey(tk.player.id));

            if (waitingPool.size() < MATCH_SIZE) return;

            waitingPool.sort(Comparator.comparingInt(tk -> tk.player.mmr));

            int i = 0;
            while (i + MATCH_SIZE <= waitingPool.size()) {
                QueueTicket first = waitingPool.get(i);
                QueueTicket last  = waitingPool.get(i + MATCH_SIZE - 1);
                int spread = last.player.mmr - first.player.mmr;

                long oldestWaitMs = 0;
                for (int j = i; j < i + MATCH_SIZE; j++) {
                    oldestWaitMs = Math.max(oldestWaitMs, waitingPool.get(j).waitMs());
                }

                int allowed = Math.min(
                    MAX_MMR_RANGE,
                    (int) (BASE_MMR_RANGE + MMR_RANGE_PER_SEC * (oldestWaitMs / 1000))
                );

                if (spread <= allowed) {
                    List<QueueTicket> group = new ArrayList<>(waitingPool.subList(i, i + MATCH_SIZE));
                    buildAndPublishMatch(group);
                    for (int j = 0; j < MATCH_SIZE; j++) waitingPool.remove(i);
                } else {
                    i++;
                }
            }
        } catch (Throwable err) {
            err.printStackTrace();
        } finally {
            poolLock.unlock();
            lastTickDurationMs = (System.nanoTime() - startNs) / 1_000_000;
            tickCount.incrementAndGet();
        }
    }

    private void buildAndPublishMatch(List<QueueTicket> group) {
        List<Player> sorted = new ArrayList<>();
        for (QueueTicket tk : group) sorted.add(tk.player);
        sorted.sort((a, b) -> Integer.compare(b.mmr, a.mmr));

        List<Player> teamA = new ArrayList<>();
        List<Player> teamB = new ArrayList<>();
        for (int idx = 0; idx < sorted.size(); idx++) {
            int round = idx / 2;
            boolean even = (idx % 2 == 0);
            boolean toA = (round % 2 == 0) == even;
            (toA ? teamA : teamB).add(sorted.get(idx));
        }

        long mid = nextMatchId.getAndIncrement();
        Match match = new Match(mid, teamA, teamB);
        matches.put(mid, match);
        totalMatchesCreated.incrementAndGet();

        for (Player p : teamA) { playerToMatch.put(p.id, mid); playerToTicket.remove(p.id); }
        for (Player p : teamB) { playerToMatch.put(p.id, mid); playerToTicket.remove(p.id); }

        matchSetupPool.submit(() -> setupMatch(match));
    }

    private void setupMatch(Match m) {
        // Long enough that the parallelism is obvious: with 1 worker,
        // matches set up one after another; with 4, four at a time.
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.printf(
            "[match-setup] Match #%d ready — Team A %d MMR vs Team B %d MMR (diff %d)%n",
            m.id, m.teamAMmr(), m.teamBMmr(), Math.abs(m.teamAMmr() - m.teamBMmr())
        );
    }

    public void shutdown() {
        scheduler.shutdownNow();
        botPopulator.shutdownNow();
        matchSetupPool.shutdownNow();
    }
}
