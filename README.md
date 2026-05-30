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
| `ThreadPoolExecutor` (8 workers) | `ApiServer.httpPool` | HTTP requests are handled in parallel by 8 worker threads. |
| `LinkedBlockingQueue<QueueTicket>` | `MatchmakingService.incoming` | Producer/consumer hand-off: HTTP threads push tickets, the matchmaker thread drains them. |
| `ScheduledExecutorService` (tick) | `MatchmakingService.scheduler` | The matchmaking loop runs every 500 ms on a dedicated daemon thread. |
| `ScheduledExecutorService` (populator) | `MatchmakingService.botPopulator` | A second scheduled thread keeps the queue full of bots so the service is always alive. Target size scales with the setup pool. |
| `ThreadPoolExecutor` (resizable) | `MatchmakingService.matchSetupPool` | Post-match work runs here. Resizable at runtime via `/api/config/setupThreads` — more workers means more matches set up in parallel. |
| `ConcurrentHashMap` | `PlayerService.players`, `MatchmakingService.matches`, `playerToMatch`, `playerToTicket` | Lock-free reads, fine-grained writes. Lets the polling status endpoint stay fast under concurrent updates. |
| `AtomicLong` | `nextId`, `nextMatchId`, `totalMatchesCreated`, `tickCount`, `botsSpawnedTotal` | Lock-free unique IDs and counters surfaced to the live stats endpoint. |
| `ReentrantLock` | `MatchmakingService.poolLock` | Guards the sorted in-memory pool while a tick is reading, sorting, and mutating it. |
| `volatile` long | `lastTickDurationMs` | Cross-thread visibility for the most recent tick duration. |
| Daemon threads + JVM shutdown hook | `Main` | Clean shutdown of all four thread pools without `System.exit`. |

### How a match forms (concurrency flow)

Four independent threads cooperate around two shared structures (a `LinkedBlockingQueue` and a sorted in-memory pool guarded by a `ReentrantLock`). Producers feed the queue, the matchmaker drains and matches, the setup pool finalises in parallel.

```
HTTP threads               Bot populator           Matchmaker tick           Setup workers
(8-pool, producer)         (1 thread, producer)    (1 thread, consumer)      (N threads, configurable)
   │                          │                       │                          │
   │ playerToTicket.put       │ createBot + join      │                          │
   │ incoming.offer(ticket) ──┴──► incoming queue ────┤                          │
   │                                                  │  poolLock.lock           │
   │                                                  │  drain incoming → pool   │
   │                                                  │  sort by MMR ascending   │
   │                                                  │  scan window of 10       │
   │                                                  │  build teams (snake)     │
   │                                                  │  matches.put             │
   │                                                  │  playerToMatch.put       │
   │                                                  │  setupPool.submit ──────►│ sleep 800 ms
   │                                                  │  poolLock.unlock         │ println [match-setup]
   │                                                  │                          │
   │ GET /queue/status                                                           │
   │  matchFor(id) ── ConcurrentHashMap read, lock-free ─────────────────────────┘
   │  returns MATCHED + match payload
```

Each arrow `─►` crosses a thread boundary. The shared state on those arrows uses thread-safe types only: `LinkedBlockingQueue`, `ConcurrentHashMap`, `AtomicLong`, and one `ReentrantLock` for the tick's critical section.

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
            ├── Login.jsx                    ← enter a name to register
            ├── Lobby.jsx                    ← rank / MMR + "Find Match" button
            ├── Queue.jsx                    ← live timer while waiting
            ├── MatchScreen.jsx              ← two team cards, balanced MMR
            └── LiveStats.jsx                ← always-visible sidebar showing the 4 thread pools at work
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
Bot populator started (initial target 24 bots, every 500 ms)
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

1. Enter a name → you're assigned a random MMR and rank.
2. The queue is **already full of bots** — the populator keeps a target population running in the background.
3. Click **Find Match (5v5)** → you'll be matched within a second.
4. The match screen shows both teams, balanced by MMR. **YOU** is highlighted; bots are tagged.
5. Click **Back to Lobby** to release the match (calls `POST /api/match/leave`) and queue again.
6. The backend logs each match formation:
   `[match-setup] Match #1 ready — Team A 7250 MMR vs Team B 7180 MMR (diff 70)`

### 4. See the parallelism

The sidebar shows a **Live System Activity** panel with the four thread pools, plus a dropdown to **resize the match-setup pool live** (1, 2, 4, 8). Switch from 1 to 8 workers and watch:

- the capacity bar widen from 1 cell to 8 cells,
- the green pulse dots light up simultaneously (multiple setups in parallel),
- the **matches formed** counter accelerate noticeably (~6× faster at pool=8 vs pool=1).

The queue target scales with the pool size, so larger pools always have enough backlog to keep every worker busy.

---

## REST API

| Method | Path | Body / Query | Response |
|---|---|---|---|
| POST | `/api/players` | `{"name": "..."}` | `{id, name, mmr, rank, bot}` |
| POST | `/api/queue/join` | `{"playerId": 1}` | `{"queued": true}` |
| POST | `/api/queue/leave` | `{"playerId": 1}` | `{"removed": true}` |
| GET  | `/api/queue/status` | `?playerId=1` | `{state: IDLE\|QUEUED\|MATCHED, queueTimeMs, match?}` |
| POST | `/api/match/leave` | `{"playerId": 1}` | `{"released": true}` — clears the player→match mapping so the next status poll returns `IDLE`. |
| GET  | `/api/stats` | — | `{waiting, totalMatches, activeMatches, tickCount, lastTickMs, botsSpawned, httpActive, httpSize, setupActive, setupSize}` |
| POST | `/api/config/setupThreads` | `{"size": 4}` | `{"size": 4}` — live resize of the match-setup pool (1..16). |

CORS is wide-open (`*`) so the Vite dev server can talk to it directly.

---

## Why this is parallel programming

Four independent thread pools cooperate every time you use the service:

1. **HTTP request pool (8 workers).** Multiple browsers can register, queue, and poll at the same time without blocking each other.
2. **Matchmaker tick (1 thread).** Runs the matching loop every 500 ms, completely independent of the HTTP layer. It reads from a thread-safe queue that the HTTP handlers and the bot populator both write to.
3. **Bot populator (1 thread).** Keeps the queue continuously populated so the matchmaker always has material to work with. Target size and refill rate scale with the size of the setup pool.
4. **Match-setup pool (1–16 workers, configurable live).** Each formed match is "set up" here. With one worker, matches set up one at a time; with eight, eight matches set up simultaneously. The user can resize this pool from the UI dropdown and watch the throughput change.

State shared across these threads uses `ConcurrentHashMap` everywhere except the sorted in-memory pool inside the tick, which is guarded by a `ReentrantLock` because it is read, sorted, and mutated in one critical section. Counters like the tick count, total matches, and bots spawned are `AtomicLong` so the `/api/stats` endpoint can read them without contention.
