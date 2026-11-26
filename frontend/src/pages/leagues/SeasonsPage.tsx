import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiPost, type SeasonSummary } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonStatusLabel } from "./seasonStatus";

export default function SeasonsPage() {
  const { league, reload } = useLeague();
  const seasons = useApi<SeasonSummary[]>(`/leagues/${league.id}/seasons`);
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const season = await apiPost<SeasonSummary>(`/leagues/${league.id}/seasons`, { name });
      reload(); // the league's current season and tabs change
      navigate(`../seasons/${season.id}`);
    } catch (err) {
      setError((err as Error).message);
    }
  };

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
        <>
          <h4>New season</h4>
          <p className="muted">Each season has its own commissioners, players, card pool, draft and rules. Members and history carry over. You commission every season; you can add co-commissioners per season.</p>
          <form onSubmit={create}>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Season 1: March 2008" maxLength={60} required />
            <button type="submit" disabled={!name.trim()}>Create season</button>
          </form>
          {error && <p className="bad">{error}</p>}
        </>
      )}
    </>
  );
}
