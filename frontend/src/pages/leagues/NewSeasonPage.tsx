import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { apiPost, type SeasonSummary } from "../../api/client";
import { EmptyState } from "../../components/Feedback";
import { useLeague } from "./LeagueLayout";

// "Start from" (YGO-32): name the new season, and start it blank (with sensible defaults) or from
// an earlier season, ticking what to bring over. Creating it opens the setup wizard at step 1.

export default function NewSeasonPage() {
  const { league, reload } = useLeague();
  const navigate = useNavigate();
  const earlier = league.seasons;
  const [name, setName] = useState(suggestName(earlier.map((s) => s.name)));
  // The season being played (or the newest) is the likeliest to copy.
  const [from, setFrom] = useState<string>(league.currentSeasonId ?? earlier[0]?.id ?? "");
  const [copy, setCopy] = useState(earlier.length > 0);
  const [parts, setParts] = useState({ copyPlayers: true, copyRules: true, copyDraft: true, copyPool: true });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (league.myRole !== "OWNER") {
    return <EmptyState title="Only the league owner creates seasons"><p>Ask them to start the next one.</p></EmptyState>;
  }

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = await apiPost<SeasonSummary>(`/leagues/${league.id}/seasons`, {
        name, guided: true, ...(copy && from ? { copyFrom: from, ...parts } : {}),
      });
      reload();
      navigate(`/leagues/${league.id}/seasons/${s.id}/setup/1`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };
  const part = (key: keyof typeof parts, label: string, hint: string) => (
    <label className="check">
      <input type="checkbox" checked={parts[key]} onChange={(e) => setParts({ ...parts, [key]: e.target.checked })} /> {label}
      <span className="muted small"> · {hint}</span>
    </label>
  );

  return (
    <form className="new-season" onSubmit={create}>
      <h3>New season</h3>
      <label className="field">Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} required autoFocus />
      </label>

      <h5>Start from</h5>
      <div className="choice-cards">
        <label className={`choice${!copy ? " on" : ""}`}>
          <input type="radio" name="start" checked={!copy} onChange={() => setCopy(false)} />
          <strong>A blank season</strong>
          <span className="muted small">Sensible defaults (round-robin, best of 3) that you adjust as you go.</span>
        </label>
        <label className={`choice${copy ? " on" : ""}${earlier.length === 0 ? " disabled" : ""}`}>
          <input type="radio" name="start" checked={copy} onChange={() => setCopy(true)} disabled={earlier.length === 0} />
          <strong>A copy of an earlier season</strong>
          <span className="muted small">{earlier.length === 0 ? "This is the league's first season." : "Bring over what worked; dates are left for you to set."}</span>
        </label>
      </div>
      {copy && earlier.length > 0 && (
        <div className="panel copy-parts">
          <label className="field">Copy from
            <select value={from} onChange={(e) => setFrom(e.target.value)}>
              {earlier.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          {part("copyPlayers", "Players and commissioners", "with their team names; you'll draw new seats")}
          {part("copyRules", "Rules", "format, competition, trades and claims, results, playoffs")}
          {part("copyDraft", "Draft settings", "order, rounds and timer; not the date")}
          {part("copyPool", "Card pool", "the final pool, with its exceptions and flags")}
        </div>
      )}

      <p className="muted small">Next, a short guided setup walks through the rest. Everything can be changed afterwards.</p>
      {error && <p className="bad">{error}</p>}
      <p className="form-actions">
        <Link className="button ghost" to={`/leagues/${league.id}/seasons`}>Cancel</Link>
        <button type="submit" className="primary" disabled={busy || !name.trim()}>Create and set up</button>
      </p>
    </form>
  );
}

/** "Season 3" after "Season 2", else "Season N+1". */
function suggestName(names: string[]): string {
  for (const n of names) {
    const m = n.match(/^(.*?)(\d+)$/);
    if (m) return `${m[1]}${Number(m[2]) + 1}`;
  }
  return `Season ${names.length + 1}`;
}
