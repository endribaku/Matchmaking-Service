# Matchmaking Service — Parallel Programming Project

A small 5v5 matchmaking service inspired by competitive games like CS2, Valorant, and League of Legends. Players queue up with an MMR rating; a background matchmaker continuously pairs 10 skill-close players into two balanced teams.

The point of the project is to demonstrate `java.util.concurrent` primitives in a context that maps to a real-world problem (the matchmaking systems that Valve, Riot, and Blizzard run at scale). The implementation is intentionally small — not the same architecture as those companies use, just the same class of problem solved with the same building blocks.

---

## Stack

- **Backend** — Java 17+, plain `com.sun.net.httpserver.HttpServer`, single external dependency (Jackson for JSON). Maven build.
- **Frontend** — React 18 + Vite. Plain JavaScript, no TypeScript.

No databases, no frameworks beyond Jackson. Everything lives in memory while the server is running.

---

## Parallel programming concepts used

All concurrency lives in [`MatchmakingService.java`](backend/src/main/java/com/matchmaking/service/MatchmakingService.java) and [`ApiServer.java`](backend/src/main/java/com/matchmaking/api/ApiServer.java).

| Primitive | Where | Purpose |
|---|---|---|
| `ExecutorService` (fixed pool) | `ApiServer` (`HttpServer.setExecutor`) | HTTP requests are handled in parallel by 8 worker threads. |
| `LinkedBlockingQueue<QueueTicket>` | `MatchmakingService.incoming` | Producer/consumer hand-off: HTTP threads push tickets, the matchmaker thread drains them. |
| `ScheduledExecutorService` | `MatchmakingService.scheduler` | The matchmaking tick runs every 500 ms on a dedicated daemon thread. |
| `ExecutorService` (fixed pool of 2) | `MatchmakingService.matchSetupPool` | After a match is formed, "setup" (simulated server allocation) runs in parallel on a separate pool — multiple matches can be prepared at once without blocking the tick. |
| `ConcurrentHashMap` | `PlayerService.players`, `MatchmakingService.matches`, `playerToMatch`, `playerToTicket` | Lock-free reads, fine-grained writes. Lets the polling status endpoint stay fast under concurrent updates. |
| `AtomicLong` | `nextId`, `nextMatchId`, `totalMatchesCreated` | Lock-free unique IDs and counters. |
| `ReentrantLock` | `MatchmakingService.poolLock` | Guards the sorted in-memory pool while a tick is reading, sorting, and mutating it. |
| Daemon threads + JVM shutdown hook | `Main` | Clean shutdown without `System.exit`. |

### How a match forms (concurrency flow)

```
HTTP thread (POST /queue/join)        Matchmaker tick thread        Setup worker threads
        │                                       │                            │
        │  playerToTicket.put()                 │                            │
        │  incoming.offer(ticket) ─────────────►│                            │
        │                                       │  poolLock.lock()           │
        │                                       │  drain incoming → pool     │
        │                                       │  sort by MMR               │
        │                                       │  find window of 10         │
        │                                       │  build teams (snake draft) │
        │                                       │  matches.put(...)          │
        │                                       │  playerToMatch.put(...)    │
        │                                       │  matchSetupPool.submit ───►│
        │                                       │  poolLock.unlock()         │  sleep 150ms
HTTP thread (GET /queue/status)                                              │  println(...)
        │                                                                    │
        │  matchFor(playerId) ─── ConcurrentHashMap read, no lock ───────────┘
        │  returns MATCHED + match payload
```

---

## Matchmaking algorithm

Every 500 ms the matchmaker thread:

1. **Drain.** Pulls new arrivals from the `BlockingQueue` into an in-memory pool. Players who cancelled are filtered out.
2. **Sort.** Sorts the pool by MMR ascending — adjacent players are skill-close.
3. **Scan.** Walks the pool with a sliding window of 10. Forms a match whenever the MMR spread within a window fits the allowed tolerance.
4. **Relax.** The tolerance starts at 300 MMR and widens by 100 MMR for every second the longest-waiting player in the window has been in queue, capped at 1500. This mirrors real matchmakers: sit longer, get matched with a wider skill range.
5. **Balance.** When 10 are chosen, builds two teams via **snake draft**: sort by MMR descending, then assign in pattern `A, B, B, A, A, B, B, A, A, B`. Keeps team totals close (typical diff under 100 MMR across two 5-stacks).
6. **Hand off.** Submits a `setupMatch` task to a parallel worker pool, so the matchmaker tick keeps going.

---

## Project structure

```
Matchmaking Service/
├── README.md                                ← this file
├── backend/
│   ├── pom.xml                              ← Maven config (single dep: jackson-databind)
│   └── src/main/java/com/matchmaking/
│       ├── Main.java                        ← bootstraps server + matchmaker
│       ├── model/
│       │   ├── Player.java                  ← id, name, MMR, bot flag
│       │   ├── Rank.java                    ← Silver / Gold / MG / DMG / Global tiers
│       │   ├── QueueTicket.java             ← player + joinedAt timestamp
│       │   └── Match.java                   ← id, teamA, teamB
│       ├── service/
│       │   ├── PlayerService.java           ← thread-safe player registry
│       │   └── MatchmakingService.java      ← THE concurrency showcase
│       └── api/
│           ├── ApiServer.java               ← HttpServer + REST handlers
│           └── Json.java                    ← thin Jackson wrapper
└── frontend/
    ├── package.json, vite.config.js, index.html
    └── src/
        ├── main.jsx, App.jsx, api.js, App.css
        └── components/
            ├── Login.jsx                    ← enter a name
            ├── Lobby.jsx                    ← rank / MMR, "Find Match" + "Spawn Bots"
            ├── Queue.jsx                    ← live timer + waiting count
            └── MatchScreen.jsx              ← two team cards, balanced MMR
```

---

## How to run

### Prerequisites

- Java 17+ (tested with Java 22)
- Maven 3.6+
- Node.js 18+
- npm

### 1. Start the backend

```bash
cd backend
mvn compile exec:java
```

The server listens on `http://localhost:8080`. You should see:

```
Matchmaker started (tick every 500 ms)
API listening on http://localhost:8080
```

To run from a packaged jar instead:

```bash
mvn package
java -cp "target/matchmaking-backend.jar:target/lib/*" com.matchmaking.Main
```

### 2. Start the frontend (in another terminal)

```bash
cd frontend
npm install         # only the first time
npm run dev
```

Open <http://localhost:5173>.

### 3. Use it

1. Enter a name → assigned random MMR and rank.
2. Click **Find Match (5v5)** → enters queue.
3. Click **+9 Bots** → spawns 9 bots near your MMR, all queued. Within ~2–3 seconds the matchmaker forms a match.
4. The match screen shows both teams, balanced by MMR. **YOU** is highlighted; bots are tagged.
5. The backend logs each created match:
   `[match-setup] Match #1 ready — Team A 3969 MMR vs Team B 3897 MMR (diff 72)`

---

## REST API

| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST | `/api/players` | `{"name": "..."}` | `{id, name, mmr, rank, bot}` |
| POST | `/api/queue/join` | `{"playerId": 1}` | `{"queued": true}` |
| POST | `/api/queue/leave` | `{"playerId": 1}` | `{"removed": true}` |
| GET | `/api/queue/status` | `?playerId=1` | `{state: IDLE\|QUEUED\|MATCHED, queueTimeMs, waiting, match?}` |
| POST | `/api/bots/spawn` | `{"count": 9, "nearMmr": 1500}` | `{spawned: [...]}` |
| GET | `/api/stats` | — | `{waiting, totalMatches, activeMatches}` |

CORS is wide-open (`*`) so the Vite dev server can talk to it directly.

---

## Why this is parallel programming

Three independent things happen concurrently every time you use the service:

1. **HTTP requests** run in parallel on the 8-thread pool. Two browsers can register, queue, and poll at the same time without blocking each other.
2. **The matchmaker tick** runs on its own thread every 500 ms, completely independent of the HTTP layer. It reads from a thread-safe queue that HTTP handlers write to.
3. **Match setup** runs on a third pool. Each formed match is "set up" in parallel — if 5 matches form on the same tick, all 5 setup tasks run concurrently on the 2-worker pool.

The state shared across these threads (player registry, match registry, the player→match index) uses `ConcurrentHashMap` everywhere except the sorted pool inside the tick, which is guarded by a `ReentrantLock` because it's read, sorted, and mutated in one critical section.
