const BASE = 'http://localhost:8080/api'

async function req(path, opts = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  })
  if (!res.ok) {
    let msg = res.statusText
    try { msg = (await res.json()).error || msg } catch {}
    throw new Error(msg)
  }
  return res.json()
}

export const api = {
  register:   (name)     => req('/players',      { method: 'POST', body: JSON.stringify({ name }) }),
  joinQueue:  (playerId) => req('/queue/join',   { method: 'POST', body: JSON.stringify({ playerId }) }),
  leaveQueue: (playerId) => req('/queue/leave',  { method: 'POST', body: JSON.stringify({ playerId }) }),
  leaveMatch: (playerId) => req('/match/leave',  { method: 'POST', body: JSON.stringify({ playerId }) }),
  status:     (playerId) => req(`/queue/status?playerId=${playerId}`),
  stats:      ()         => req('/stats'),
  setSetupThreads: (size) => req('/config/setupThreads', { method: 'POST', body: JSON.stringify({ size }) }),
}
