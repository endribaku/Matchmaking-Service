import { useEffect, useState } from 'react'
import { api } from './api.js'
import Login from './components/Login.jsx'
import Lobby from './components/Lobby.jsx'
import Queue from './components/Queue.jsx'
import MatchScreen from './components/MatchScreen.jsx'
import LiveStats from './components/LiveStats.jsx'

export default function App() {
  const [player, setPlayer] = useState(() => {
    const s = localStorage.getItem('mm.player')
    return s ? JSON.parse(s) : null
  })
  const [view, setView] = useState('lobby')
  const [match, setMatch] = useState(null)
  const [queueInfo, setQueueInfo] = useState({ queueTimeMs: 0 })
  const [error, setError] = useState(null)

  useEffect(() => {
    if (player) localStorage.setItem('mm.player', JSON.stringify(player))
  }, [player])

  useEffect(() => {
    if (!player || view !== 'queue') return
    let cancelled = false
    const poll = async () => {
      try {
        const s = await api.status(player.id)
        if (cancelled) return
        if (s.state === 'MATCHED') {
          setMatch(s.match)
          setView('match')
        } else if (s.state === 'IDLE') {
          setView('lobby')
        } else {
          setQueueInfo({ queueTimeMs: s.queueTimeMs })
        }
      } catch (e) {
        if (!cancelled) setError(e.message)
      }
    }
    poll()
    const id = setInterval(poll, 800)
    return () => { cancelled = true; clearInterval(id) }
  }, [player, view])

  if (!player) return <Login onLogin={setPlayer} />

  let main
  if (view === 'match' && match) {
    main = <MatchScreen
      player={player}
      match={match}
      onLeave={async () => {
        try { await api.leaveMatch(player.id) } catch {}
        setMatch(null)
        setView('lobby')
      }}
    />
  } else if (view === 'queue') {
    main = <Queue
      player={player}
      info={queueInfo}
      onCancel={async () => { await api.leaveQueue(player.id); setView('lobby') }}
    />
  } else {
    main = <Lobby
      player={player}
      onQueue={async () => {
        try { await api.joinQueue(player.id); setView('queue'); setError(null) }
        catch (e) { setError(e.message) }
      }}
      onLogout={() => { localStorage.removeItem('mm.player'); setPlayer(null) }}
    />
  }

  return (
    <div className="layout">
      <main className="layout-main">{main}</main>
      <LiveStats />
      {error && <div className="toast">{error}</div>}
    </div>
  )
}
