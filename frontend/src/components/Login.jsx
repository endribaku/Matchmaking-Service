import { useState } from 'react'
import { api } from '../api.js'

export default function Login({ onLogin }) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)

  async function submit(e) {
    e.preventDefault()
    if (!name.trim()) return
    setBusy(true); setErr(null)
    try {
      const p = await api.register(name.trim())
      onLogin(p)
    } catch (e) {
      setErr(e.message + ' — is the backend running on :8080?')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="screen center">
      <div className="card">
        <div className="brand">MATCHMAKING</div>
        <p className="muted">Pick a player name. You'll be assigned a random MMR & rank.</p>
        <form onSubmit={submit}>
          <input
            autoFocus
            placeholder="player name"
            value={name}
            onChange={e => setName(e.target.value)}
            maxLength={20}
          />
          <button className="primary" type="submit" disabled={busy || !name.trim()}>
            {busy ? '…' : 'Enter'}
          </button>
        </form>
        {err && <div className="error">{err}</div>}
      </div>
    </div>
  )
}
