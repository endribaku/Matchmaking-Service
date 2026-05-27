export default function MatchScreen({ player, match, onLeave }) {
  const isYou = (p) => p.id === player.id
  const diff = Math.abs(match.teamAMmr - match.teamBMmr)

  return (
    <div className="match">
      <div className="row between match-header">
        <div>
          <div className="muted small">Match Found</div>
          <h2>Match #{match.id}</h2>
        </div>
        <div className="balance">
          <div className="muted small">MMR diff</div>
          <div className="balance-num">{diff}</div>
        </div>
      </div>

      <div className="teams">
        <TeamCard label="Team A" team={match.teamA} total={match.teamAMmr} isYou={isYou} />
        <div className="vs">VS</div>
        <TeamCard label="Team B" team={match.teamB} total={match.teamBMmr} isYou={isYou} />
      </div>

      <div className="center-text">
        <button className="primary" onClick={onLeave}>Back to Lobby</button>
      </div>
    </div>
  )
}

function TeamCard({ label, team, total, isYou }) {
  return (
    <div className="team">
      <div className="row between">
        <h3>{label}</h3>
        <span className="muted">{total} MMR</span>
      </div>
      <ul>
        {team.map(p => (
          <li key={p.id} className={isYou(p) ? 'you' : ''}>
            <span className="pname">
              {p.name}
              {p.bot && <span className="badge bot">BOT</span>}
              {isYou(p) && <span className="badge me">YOU</span>}
            </span>
            <span className="prank">{p.rank} · {p.mmr}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
