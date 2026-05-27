import { useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * Always-visible panel showing the four parallel components of the
 * backend at work in real time. Polls /api/stats once per second.
 */
export default function LiveStats() {
  const [s, setS] = useState(null)
  const [prevTick, setPrevTick] = useState(0)
  const [tickFlash, setTickFlash] = useState(false)

  useEffect(() => {
    let cancelled = false
    const tick = async () => {
      try {
        const data = await api.stats()
        if (cancelled) return
        setS(data)
      } catch {}
    }
    tick()
    const id = setInterval(tick, 1000)
    return () => { cancelled = true; clearInterval(id) }
  }, [])

  // Flash the matchmaker indicator when the tick counter advances.
  useEffect(() => {
    if (!s) return
    if (s.tickCount !== prevTick) {
      setPrevTick(s.tickCount)
      setTickFlash(true)
      const t = setTimeout(() => setTickFlash(false), 300)
      return () => clearTimeout(t)
    }
  }, [s])

  if (!s) return null

  return (
    <aside className="stats">
      <div className="stats-title">Live System Activity</div>

      <div className="stat-row">
        <div className="stat-big">
          <div className="stat-num">{s.waiting}</div>
          <div className="stat-label">in queue</div>
        </div>
        <div className="stat-big">
          <div className="stat-num">{s.totalMatches}</div>
          <div className="stat-label">matches formed</div>
        </div>
      </div>

      <div className="stats-section">Parallel threads</div>

      <ThreadRow
        name="Matchmaker tick"
        detail={`tick #${s.tickCount} · ${s.lastTickMs}ms`}
        active={tickFlash}
      />
      <ThreadRow
        name="Bot populator"
        detail={`${s.botsSpawned} bots spawned`}
        active={s.waiting < 20}
      />
      <ThreadRow
        name="Match-setup pool"
        detail={`${s.setupActive} / ${s.setupSize} workers busy`}
        active={s.setupActive > 0}
        capacity={{ used: s.setupActive, total: s.setupSize }}
      />
      <ThreadRow
        name="HTTP request pool"
        detail={`${s.httpActive} / ${s.httpSize} workers busy`}
        active={s.httpActive > 0}
        capacity={{ used: s.httpActive, total: s.httpSize }}
      />

      <div className="stats-section">Tune parallelism</div>
      <div className="config-row">
        <label>Match-setup workers</label>
        <select
          value={s.setupSize}
          onChange={e => api.setSetupThreads(Number(e.target.value)).catch(() => {})}
        >
          <option value={1}>1</option>
          <option value={2}>2</option>
          <option value={4}>4</option>
          <option value={8}>8</option>
        </select>
      </div>
      <div className="config-hint">
        Each setup takes ~800 ms. With 1 worker, matches set up one at a
        time; with 4, four at once.
      </div>
    </aside>
  )
}

function ThreadRow({ name, detail, active, capacity }) {
  return (
    <div className="thread-row">
      <div className={'thread-dot' + (active ? ' active' : '')} />
      <div className="thread-info">
        <div className="thread-name">{name}</div>
        <div className="thread-detail">{detail}</div>
        {capacity && (
          <div className="capacity-bar">
            {Array.from({ length: capacity.total }).map((_, i) => (
              <span key={i} className={'cap-cell' + (i < capacity.used ? ' on' : '')} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
