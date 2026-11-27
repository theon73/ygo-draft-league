import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import LeagueCards from "./LeagueCards";
import { apiPost, type LeagueSummary } from "../../api/client";
import { useSession } from "../../auth/session";
import { useApi } from "../../hooks/useApi";
import { EmptyState, Loading } from "../../components/Feedback";

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

  if (!session) return <Loading />;
  if (!user) {
    return <EmptyState title="My leagues"><p>Sign in to see your leagues or create one.</p></EmptyState>;
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
      <h1>My leagues</h1>
      {leagues.error && <p className="bad">Couldn't load your leagues ({leagues.error})</p>}
      {leagues.loading && <Loading />}
      {leagues.data &&
        (leagues.data.length === 0 ? (
          <EmptyState title="No leagues yet">
            <p>Create one below, or ask a league owner for an invite link.</p>
          </EmptyState>
        ) : (
          <LeagueCards leagues={leagues.data} />
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
        <button type="submit" className="primary" disabled={saving || !name.trim()}>Create league</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </section>
  );
}
