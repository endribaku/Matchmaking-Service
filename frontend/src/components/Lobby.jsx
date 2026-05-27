export default function Lobby({ player, onQueue, onLogout }) {
  return (
    <div className="card lobby">
      <div className="row between">
        <div>
          <div className="muted small">Logged in as</div>
          <h2>{player.name}</h2>
        </div>
        <div className="rank">
          <div className="rank-label">{player.rank}</div>
          <div className="mmr">{player.mmr} MMR</div>
        </div>
      </div>

      <button className="primary big" onClick={onQueue}>
        Find Match (5v5)
      </button>

      <p className="muted small center-text" style={{ marginTop: 20 }}>
        The matchmaker has bots in queue already.<br/>
        Click above to join and you'll be matched within a few seconds.
      </p>

      <button className="ghost" onClick={onLogout}>Log out</button>
    </div>
  )
}
