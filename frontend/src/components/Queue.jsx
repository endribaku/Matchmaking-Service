export default function Queue({ player, info, onCancel }) {
  const seconds = Math.floor(info.queueTimeMs / 1000)
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0')
  const ss = String(seconds % 60).padStart(2, '0')

  return (
    <div className="card">
      <h2>Searching for match…</h2>
      <div className="timer">{mm}:{ss}</div>
      <div className="muted small center-text" style={{ marginTop: 8 }}>
        Looking for 9 others near {player.mmr} MMR.<br/>
        MMR tolerance widens the longer you wait.
      </div>
      <div className="pulse" />
      <button className="danger" onClick={onCancel}>Cancel</button>
    </div>
  )
}
