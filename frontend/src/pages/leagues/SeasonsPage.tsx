import { Link } from "react-router-dom";
import type { SeasonSummary } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonStatusLabel } from "./seasonStatus";

export default function SeasonsPage() {
  const { league } = useLeague();
  const seasons = useApi<SeasonSummary[]>(`/leagues/${league.id}/seasons`);

  return (
    <>
      <h3>Seasons</h3>
      {seasons.error && <p className="bad">Couldn't load seasons ({seasons.error})</p>}
      {seasons.data &&
        (seasons.data.length === 0 ? (
          <p className="muted">No seasons yet.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Season</th><th>Status</th><th>Players</th><th>Created</th></tr>
            </thead>
            <tbody>
              {seasons.data.map((s) => (
                <tr key={s.id}>
                  <td><Link to={s.id}>{s.name}</Link>{s.id === league.currentSeasonId && <span className="badge">current</span>}</td>
                  <td>{seasonStatusLabel[s.status]}</td>
                  <td>{s.playerCount}</td>
                  <td>{new Date(s.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
      {league.myRole === "OWNER" && (
        <p><Link className="button primary" to="new">New season</Link>{" "}
          <span className="muted small">Start blank or from an earlier season, then a short guided setup.</span></p>
      )}
    </>
  );
}
