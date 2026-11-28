import { useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { apiPatch, apiPost, type InviteView, type LeagueDetail, type LeagueSummary, type SeasonSummary, type Weekday } from "../../api/client";
import { useSession } from "../../auth/session";
import { EmptyState, Loading, useToast } from "../../components/Feedback";
import { useApi } from "../../hooks/useApi";

// Creating a league (YGO-32), as one flow that ends in its first season's setup, so a new
// commissioner never lands in an empty league:
//   1. The league: name, time zone and when its week starts.
//   2. Its people: an invite link to share, and placeholders for anyone without an account yet.
//   3. The first season: a name, then the season setup wizard.
// The league exists after step 1, so the step and league live in the URL (?league=…&step=2) and a
// reload or a return visit picks up where it was.

const ZONES: string[] = Intl.supportedValuesOf("timeZone");
const WEEKDAYS: Weekday[] = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const dayLabel = (d: string) => d.charAt(0) + d.slice(1).toLowerCase();
const STEPS = ["The league", "Its people", "First season"];

export default function NewLeagueWizard() {
  const { session } = useSession();
  const [params, setParams] = useSearchParams();
  const leagueId = params.get("league");
  const step = leagueId ? Math.min(3, Math.max(2, Number(params.get("step")) || 2)) : 1;

  if (!session) return <Loading />;
  if (!session.user) return <EmptyState title="Sign in to create a league" />;

  const to = (id: string, n: number) => setParams({ league: id, step: String(n) });
  return (
    <section className="wizard league-wizard">
      <h1>Create a league</h1>
      <ol className="wizard-steps" aria-label="Steps">
        {STEPS.map((title, i) => (
          <li key={title} className={i + 1 === step ? "current" : i + 1 < step ? "done" : undefined}>
            <span className="step-button">
              <span className="step-no">{i + 1 < step ? "✓" : i + 1}</span>
              <span className="step-title">{title}</span>
            </span>
          </li>
        ))}
      </ol>
      {step === 1 && <LeagueStep onCreated={(id) => to(id, 2)} />}
      {step === 2 && leagueId && <PeopleStep leagueId={leagueId} onNext={() => to(leagueId, 3)} />}
      {step === 3 && leagueId && <FirstSeasonStep leagueId={leagueId} onBack={() => to(leagueId, 2)} />}
      <p className="wizard-note">You can change all of this later from the league's Members and Settings.</p>
    </section>
  );
}

function LeagueStep({ onCreated }: { onCreated: (id: string) => void }) {
  const [name, setName] = useState("");
  const [zone, setZone] = useState(Intl.DateTimeFormat().resolvedOptions().timeZone);
  const [day, setDay] = useState<Weekday>("MONDAY");
  const [time, setTime] = useState("00:00");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const league = await apiPost<LeagueSummary>("/leagues", { name, timeZone: zone });
      await apiPatch(`/leagues/${league.id}`, { timeZone: zone, weekStartsOn: day, weekStartsAt: time });
      onCreated(league.id);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <form onSubmit={create}>
      <label className="field">League name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} placeholder="e.g. Friday Night Edison" required autoFocus />
      </label>
      <div className="form-grid">
        <label>Time zone
          <select value={zone} onChange={(e) => setZone(e.target.value)}>
            {(ZONES.includes(zone) ? ZONES : [zone, ...ZONES]).map((z) => <option key={z} value={z}>{z.replaceAll("_", " ")}</option>)}
          </select>
        </label>
        <label>Weeks start on
          <span className="inline-pair">
            <select value={day} onChange={(e) => setDay(e.target.value as Weekday)}>
              {WEEKDAYS.map((d) => <option key={d} value={d}>{dayLabel(d)}</option>)}
            </select>
            <input type="time" value={time} onChange={(e) => setTime(e.target.value)} required />
          </span>
        </label>
      </div>
      <p className="muted small">Deadlines, draft times and the weekly trade and claim limits all follow this zone and week.</p>
      {error && <p className="bad">{error}</p>}
      <p className="form-actions">
        <Link className="button ghost" to="/leagues">Cancel</Link>
        <button type="submit" className="primary" disabled={busy || !name.trim()}>Create league</button>
      </p>
    </form>
  );
}

function PeopleStep({ leagueId, onNext }: { leagueId: string; onNext: () => void }) {
  const league = useApi<LeagueDetail>(`/leagues/${leagueId}`);
  const invites = useApi<InviteView[]>(`/leagues/${leagueId}/invites`);
  const toast = useToast();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  if (!league.data || !invites.data) return <Loading />;

  const link = invites.data.find((i) => i.kind === "OPEN" && i.useCount < (i.maxUses ?? Infinity));
  const url = link && `${window.location.origin}/invite/${link.code}`;
  const act = async (change: () => Promise<unknown>) => {
    setError(null);
    try {
      await change();
      league.reload();
      invites.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  const copy = async () => {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      toast("Invite link copied.");
    } catch {
      toast("Couldn't copy; select the link and copy it instead.", "bad");
    }
  };
  const others = league.data.members.filter((m) => !m.you);

  return (
    <>
      <h3>Invite your players</h3>
      <p className="muted">Share one link with everyone: they sign in and join. It works until you revoke it from Members.</p>
      {url ? (
        <div className="invite-link">
          <input readOnly value={url} onFocus={(e) => e.target.select()} aria-label="Invite link" />
          <button type="button" className="primary" onClick={() => void copy()}>Copy link</button>
        </div>
      ) : (
        <p><button type="button" className="primary" onClick={() => void act(() => apiPost(`/leagues/${leagueId}/invites`, {}))}>Create an invite link</button></p>
      )}

      <h5>Not on the site yet?</h5>
      <p className="muted small">Add them as a placeholder so you can set up the season now; send them a claim link later from Members.</p>
      <form className="inline-form" onSubmit={(e) => { e.preventDefault(); void act(() => apiPost(`/leagues/${leagueId}/members/placeholders`, { displayName: name })).then(() => setName("")); }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Their name" maxLength={40} />
        <button type="submit" disabled={!name.trim()}>Add</button>
      </form>
      {error && <p className="bad">{error}</p>}
      {others.length > 0 && (
        <ul className="player-chips">
          {others.map((m) => <li key={m.id}>{m.displayName}{m.placeholder && <span className="muted small"> · placeholder</span>}</li>)}
        </ul>
      )}
      <p className="form-actions">
        <button type="button" className="primary" onClick={onNext}>Continue</button>
      </p>
    </>
  );
}

function FirstSeasonStep({ leagueId, onBack }: { leagueId: string; onBack: () => void }) {
  const navigate = useNavigate();
  const [name, setName] = useState("Season 1");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = await apiPost<SeasonSummary>(`/leagues/${leagueId}/seasons`, { name, guided: true });
      navigate(`/leagues/${leagueId}/seasons/${s.id}/setup/1`);
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };
  return (
    <form onSubmit={create}>
      <h3>Set up your first season</h3>
      <p className="muted">A season is one run of play: its players, card pool, draft and matches. Next comes a short guided setup.</p>
      <label className="field">Season name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={60} required autoFocus />
      </label>
      {error && <p className="bad">{error}</p>}
      <p className="form-actions">
        <button type="button" className="ghost" onClick={onBack}>Back</button>
        <Link className="button ghost" to={`/leagues/${leagueId}`}>Later</Link>
        <button type="submit" className="primary" disabled={busy || !name.trim()}>Create and set up</button>
      </p>
    </form>
  );
}
