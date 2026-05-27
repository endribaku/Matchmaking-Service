package com.matchmaking.api;

import com.matchmaking.model.Match;
import com.matchmaking.model.Player;
import com.matchmaking.model.QueueTicket;
import com.matchmaking.service.MatchmakingService;
import com.matchmaking.service.PlayerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thin HTTP layer over the matchmaking engine.
 *
 * The HttpServer is given a ThreadPoolExecutor (8 threads) — multiple
 * requests are handled in parallel. We keep a typed reference so /api/stats
 * can report the pool's live active-thread count.
 */
public class ApiServer {

    private final PlayerService players;
    private final MatchmakingService matchmaking;
    private final int port;

    private final ThreadPoolExecutor httpPool = new ThreadPoolExecutor(
        8, 8, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        r -> { Thread t = new Thread(r, "http-worker"); t.setDaemon(true); return t; }
    );

    private HttpServer server;

    public ApiServer(PlayerService players, MatchmakingService matchmaking, int port) {
        this.players = players;
        this.matchmaking = matchmaking;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(httpPool);

        server.createContext("/api/players",             wrap(this::handlePlayers));
        server.createContext("/api/queue/join",          wrap(this::handleJoin));
        server.createContext("/api/queue/leave",         wrap(this::handleLeave));
        server.createContext("/api/queue/status",        wrap(this::handleStatus));
        server.createContext("/api/match/leave",         wrap(this::handleMatchLeave));
        server.createContext("/api/stats",               wrap(this::handleStats));
        server.createContext("/api/config/setupThreads", wrap(this::handleSetupThreads));

        server.start();
        System.out.println("API listening on http://localhost:" + port);
    }

    public void stop() {
        if (server != null) server.stop(0);
        httpPool.shutdownNow();
    }

    // ---- handlers -------------------------------------------------------

    private void handlePlayers(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
        Map<String, Object> body = Json.readMap(ex.getRequestBody());
        Object n = body.get("name");
        if (!(n instanceof String) || ((String) n).isBlank()) {
            send(ex, 400, err("name required")); return;
        }
        Player p = players.register(((String) n).trim());
        send(ex, 200, playerDto(p));
    }

    private void handleJoin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
        long pid = readPlayerId(ex);
        Player p = players.get(pid);
        if (p == null) { send(ex, 404, err("player not found")); return; }
        boolean ok = matchmaking.joinQueue(p);
        send(ex, 200, Map.of("queued", ok));
    }

    private void handleLeave(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
        long pid = readPlayerId(ex);
        boolean ok = matchmaking.leaveQueue(pid);
        send(ex, 200, Map.of("removed", ok));
    }

    private void handleMatchLeave(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
        long pid = readPlayerId(ex);
        boolean ok = matchmaking.releaseMatch(pid);
        send(ex, 200, Map.of("released", ok));
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        long pid = queryLong(ex.getRequestURI(), "playerId");
        Player p = players.get(pid);
        if (p == null) { send(ex, 404, err("player not found")); return; }

        Match m = matchmaking.matchFor(pid);
        QueueTicket t = matchmaking.ticketFor(pid);

        Map<String, Object> resp = new LinkedHashMap<>();
        if (m != null) {
            resp.put("state", "MATCHED");
            resp.put("match", matchDto(m));
            resp.put("queueTimeMs", 0);
        } else if (t != null) {
            resp.put("state", "QUEUED");
            resp.put("queueTimeMs", t.waitMs());
        } else {
            resp.put("state", "IDLE");
            resp.put("queueTimeMs", 0);
        }
        send(ex, 200, resp);
    }

    /**
     * Resize the match-setup worker pool at runtime. Driven by the
     * dropdown in the live-stats panel — lets the user see how
     * parallelism width changes throughput.
     */
    private void handleSetupThreads(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, err("method not allowed")); return; }
        Map<String, Object> body = Json.readMap(ex.getRequestBody());
        Object o = body.get("size");
        if (!(o instanceof Number)) { send(ex, 400, err("size required")); return; }
        int size = ((Number) o).intValue();
        if (size < 1 || size > 16) { send(ex, 400, err("size must be 1..16")); return; }
        matchmaking.setSetupPoolSize(size);
        send(ex, 200, Map.of("size", size));
    }

    /**
     * Live system stats — shown in the UI so the user can see all four
     * thread pools at work in real time.
     */
    private void handleStats(HttpExchange ex) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("waiting",        matchmaking.waitingCount());
        stats.put("totalMatches",   matchmaking.totalMatches());
        stats.put("activeMatches",  matchmaking.activeMatches());
        stats.put("tickCount",      matchmaking.tickCount());
        stats.put("lastTickMs",     matchmaking.lastTickMs());
        stats.put("botsSpawned",    matchmaking.botsSpawned());
        stats.put("httpActive",     httpPool.getActiveCount());
        stats.put("httpSize",       httpPool.getMaximumPoolSize());
        stats.put("setupActive",    matchmaking.setupActive());
        stats.put("setupSize",      matchmaking.setupPoolSize());
        send(ex, 200, stats);
    }

    // ---- helpers --------------------------------------------------------

    private long readPlayerId(HttpExchange ex) {
        Map<String, Object> body = Json.readMap(ex.getRequestBody());
        Object o = body.get("playerId");
        if (o instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("playerId required");
    }

    private long queryLong(URI uri, String key) {
        String q = uri.getQuery();
        if (q == null) return 0;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }

    private Map<String, Object> playerDto(Player p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",   p.id);
        m.put("name", p.name);
        m.put("mmr",  p.mmr);
        m.put("rank", p.rankLabel());
        m.put("bot",  p.bot);
        return m;
    }

    private Map<String, Object> matchDto(Match m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id",       m.id);
        map.put("teamA",    m.teamA.stream().map(this::playerDto).toList());
        map.put("teamB",    m.teamB.stream().map(this::playerDto).toList());
        map.put("teamAMmr", m.teamAMmr());
        map.put("teamBMmr", m.teamBMmr());
        return map;
    }

    private Map<String, Object> err(String msg) { return Map.of("error", msg); }

    private void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        addCors(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private HttpHandler wrap(HttpHandler h) {
        return ex -> {
            try {
                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    addCors(ex);
                    ex.sendResponseHeaders(204, -1);
                    return;
                }
                h.handle(ex);
            } catch (Exception e) {
                e.printStackTrace();
                try { send(ex, 500, Map.of("error", String.valueOf(e.getMessage()))); }
                catch (IOException ignored) {}
            }
        };
    }
}
