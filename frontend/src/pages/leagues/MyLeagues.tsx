import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiPost, type LeagueSummary } from "../../api/client";
import { useSession } from "../../auth/session";
import { useApi } from "../../hooks/useApi";
import { roleLabel } from "./roles";

const MAX_NAME = 60; // matches LeagueService.MAX_NAME_LENGTH

export default function MyLeagues() {
  const { session } = useSession();
  const user = session?.user;
  // Refetch when the signed-in account changes (e.g. via the dev switcher).
  const leagues = useApi<LeagueSummary[]>(user ? "/leagues" : null, user?.identity);
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  if (!session) return <p className="muted">Loading…</p>;
  if (!user) {
    return (
      <section>
        <h2>My leagues</h2>
        <p>Sign in to see your leagues or create one.</p>
      </section>
    );
  }

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      // The creator's time zone becomes the league's; it can be changed later in league settings.
      const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const league = await apiPost<LeagueSummary>("/leagues", { name, timeZone });
      navigate(`/leagues/${league.id}`);
    } catch (err) {
      setError((err as Error).message);
      setSaving(false);
    }
  };

  return (
    <section>
      <h2>My leagues</h2>
      {leagues.error && <p className="bad">Couldn't load your leagues ({leagues.error})</p>}
      {leagues.data &&
        (leagues.data.length === 0 ? (
          <p className="muted">You're not in any leagues yet. Create one below, or ask a league owner for an invite.</p>
        ) : (
          <table>
            <thead>
              <tr><th>League</th><th>Your role</th><th>Members</th></tr>
            </thead>
            <tbody>
              {leagues.data.map((l) => (
                <tr key={l.id}>
                  <td><Link to={`/leagues/${l.id}`}>{l.name}</Link></td>
                  <td>{roleLabel[l.myRole]}</td>
                  <td>{l.memberCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}

      <h3>Create a league</h3>
      <p className="muted">You'll be its owner: you invite people, create seasons and commission every season.</p>
      <form onSubmit={create}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="League name"
          maxLength={MAX_NAME}
          required
        />
        <button type="submit" disabled={saving || !name.trim()}>Create</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </section>
  );
}
