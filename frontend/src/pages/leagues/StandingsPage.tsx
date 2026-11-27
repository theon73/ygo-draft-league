import { Link } from "react-router-dom";
import type { SeasonStandings, Tiebreaker } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";

// The Standings tab (YGO-26): the regular season's table from final results. A draws column
// appears only once a match has been drawn.
const TIEBREAKER: Record<Tiebreaker, string> = {
  GAME_DIFF: "game difference",
  GAMES_WON: "games won",
  HEAD_TO_HEAD: "head-to-head",
};

export default function StandingsPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const standings = useApi<SeasonStandings>(`/leagues/${league.id}/seasons/${season.id}/standings`);

  if (standings.error) return <p className="bad">Couldn't load the standings ({standings.error})</p>;
  if (!standings.data) return <p className="muted">Loading…</p>;
  const s = standings.data;
  const signed = (n: number) => (n > 0 ? `+${n}` : String(n));

  return (
    <>
      <h4>Standings</h4>
      <p className="muted">
        Ranked by wins, then {s.tiebreakers.map((t) => TIEBREAKER[t]).join(", then ")}. Only final results count
        {s.waiting > 0 && <> ({s.waiting} reported result{s.waiting === 1 ? "" : "s"} still waiting for confirmation)</>}
        {s.remaining > 0 && <>; {s.remaining} match{s.remaining === 1 ? "" : "es"} to go</>}. Results are reported on the <Link to="../schedule">Schedule</Link> tab.
      </p>
      {s.rows.length === 0 ? (
        <p className="muted">Nobody is playing this season yet.</p>
      ) : (
        <div className="table-scroll">
          <table className="standings">
            <thead>
              <tr>
                <th>#</th><th>Player</th><th title="Matches played">P</th><th title="Wins">W</th><th title="Losses">L</th>
                {s.showDraws && <th title="Draws">D</th>}
                <th title="Games won – games lost">Games</th><th title="Game difference">+/−</th>
              </tr>
            </thead>
            <tbody>
              {s.rows.map((r) => (
                <tr key={r.memberId} className={r.you ? "mine" : undefined}>
                  <td>{r.rank}</td>
                  <td>
                    <Link to={`../rosters/${r.memberId}`}>{r.name}</Link>{r.you && <span className="badge">you</span>}
                    {r.teamName && <span className="muted"> {r.teamName}</span>}
                  </td>
                  <td>{r.played}</td><td><strong>{r.wins}</strong></td><td>{r.losses}</td>
                  {s.showDraws && <td>{r.draws}</td>}
                  <td>{r.gamesWon}–{r.gamesLost}</td><td>{signed(r.gameDiff)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
