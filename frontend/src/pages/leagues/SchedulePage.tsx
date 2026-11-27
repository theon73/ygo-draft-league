import { useState } from "react";
import { Link } from "react-router-dom";
import { apiPost, type Schedule, type ScheduleWeek, type SchedulePlayer } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";

// The Schedule tab (YGO-25): this week's matchups first, then every week. Drawn automatically when
// the draft completes; commissioners can draw again until week 1 begins.
export default function SchedulePage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/schedule`;
  const schedule = useApi<Schedule>(base, String(draftVersion));
  const [mineOnly, setMineOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (schedule.error) return <p className="bad">Couldn't load the schedule ({schedule.error})</p>;
  if (!schedule.data) return <p className="muted">Loading…</p>;
  const s = schedule.data;
  const zone = league.timeZone;

  const draw = async () => {
    if (s.generated && !window.confirm("Draw a new schedule? Every matchup changes.")) return;
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/draw`);
      schedule.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const current = s.weeks.find((w) => w.current) ?? null;
  const playing = s.weeks.some((w) => w.matches.some((m) => m.mine));
  const shown = s.weeks.map((w) => (mineOnly ? { ...w, matches: w.matches.filter((m) => m.mine) } : w));
  const over = s.weeks.length > 0 && s.currentWeek > s.weeks.length;

  return (
    <>
      <h4>Schedule</h4>
      {!s.generated ? (
        <p className="muted">
          {season.status === "SETUP" || season.status === "DRAFTING"
            ? "The schedule is drawn automatically when the draft completes."
            : "There's no schedule yet."}
        </p>
      ) : (
        <p className="muted">
          Round-robin, one round a week, {s.weeks.length} weeks.{" "}
          {s.drawnBy ? `Drawn by ${s.drawnBy}` : "Drawn automatically when the draft completed"}
          {s.drawnAt && `, ${new Date(s.drawnAt).toLocaleString()}`}
          {s.draws > 1 && ` (draw ${s.draws})`}.
        </p>
      )}

      {s.canManage && (s.canGenerate ? (
        <p>
          <button onClick={() => void draw()} disabled={busy}>{s.generated ? "Draw again" : "Draw the schedule"}</button>{" "}
          <span className="muted">
            {s.generated ? "Until week 1 begins, you can draw new matchups; after that the schedule is fixed." : "A random draw of the players, recorded."}
          </span>
        </p>
      ) : s.generated && s.why && <p className="muted">{s.why}.</p>)}
      {error && <p className="bad">{error}</p>}

      {current && !over && (
        <section className="panel this-week">
          <h5>
            {s.currentWeek === 0 ? "First up: week 1" : `This week: week ${current.week}`}
            <span className="muted"> · {dates(current, zone)} · best of {current.bestOf}<Byes week={current} /></span>
          </h5>
          <Matchups week={current} />
        </section>
      )}
      {over && <p className="muted">The regular season's weeks are over.</p>}

      {s.weeks.length > 0 && (
        <>
          <div className="pool-toolbar">
            <h5 className="schedule-all">All weeks</h5>
            {playing && (
              <label className="check-inline">
                <input type="checkbox" checked={mineOnly} onChange={(e) => setMineOnly(e.target.checked)} /> Only my matches
              </label>
            )}
          </div>
          <ol className="schedule-weeks">
            {shown.map((w) => (
              <li key={w.week} className={`${w.current ? "current" : ""}${w.week < s.currentWeek ? " past" : ""}`}>
                <div className="week-head">
                  <strong>Week {w.week}</strong>
                  <span className="muted">{dates(w, zone)}{w.start ? " · " : ""}Bo{w.bestOf}<Byes week={w} /></span>
                </div>
                <Matchups week={w} compact />
              </li>
            ))}
          </ol>
        </>
      )}
    </>
  );
}

// The week's matches; byes are named in the week's heading instead (see Byes).
function Matchups({ week, compact = false }: { week: ScheduleWeek; compact?: boolean }) {
  const games = week.matches.filter((m) => m.b !== null);
  if (games.length === 0) return <p className="muted">{week.matches.length > 0 ? "You have a bye this week." : "No matches of yours this week."}</p>;
  return (
    <ul className={`matchups${compact ? " compact" : ""}`}>
      {games.map((m, i) => (
        <li key={i} className={m.mine ? "mine" : undefined}>
          <Player p={m.a} /><span className="vs">vs</span><Player p={m.b!} />
        </li>
      ))}
    </ul>
  );
}

/** " · Duelist 3 has a bye" (odd player counts: one player sits each week out). */
function Byes({ week }: { week: ScheduleWeek }) {
  const byes = week.matches.filter((m) => m.b === null);
  if (byes.length === 0) return null;
  return <> · {byes.map((m) => (m.a.you ? "you have" : `${m.a.name} has`)).join(", ")} a bye</>;
}

function Player({ p }: { p: SchedulePlayer }) {
  return (
    <span className="matchup-player">
      <Link to={`../rosters/${p.memberId}`}>{p.name}</Link>
      {p.you && <span className="badge">you</span>}
      {p.teamName && <span className="muted team"> {p.teamName}</span>}
    </span>
  );
}

/** "Mon Jan 7 – Sun Jan 13", in the league's time zone (a week ends where the next begins). */
function dates(w: ScheduleWeek, zone: string): string {
  if (!w.start || !w.end) return "";
  const fmt = (iso: string) => new Date(iso).toLocaleDateString(undefined, { timeZone: zone, weekday: "short", month: "short", day: "numeric" });
  const lastDay = new Date(new Date(w.end).getTime() - 1).toISOString();
  return `${fmt(w.start)} – ${fmt(lastDay)}`;
}
