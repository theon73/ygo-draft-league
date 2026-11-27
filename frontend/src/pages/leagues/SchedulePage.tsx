import InfoTip from "../../components/InfoTip";
import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { apiPost, type Playoffs, type Schedule, type ScheduleMatch, type ScheduleWeek, type SchedulePlayer } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import { useConfirm, Loading } from "../../components/Feedback";
import PlayerAvatar from "../../components/PlayerAvatar";
import { hasPlayoffsSection, PlayoffsSection } from "./PlayoffsPage";

// The Schedule tab (YGO-25): this week's matchups first, then every week. Drawn automatically when
// the draft completes; commissioners can draw again until week 1 begins. Results (YGO-26) are
// reported, confirmed, disputed and (by commissioners) entered from the match tiles.

export type Action = "report" | "confirm" | "enter";
interface Selected { matchId: string; action: Action; where: "current" | "all" }
export default function SchedulePage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/schedule`;
  const schedule = useApi<Schedule>(base, String(draftVersion));
  const seasonBase = `/leagues/${league.id}/seasons/${season.id}`;
  // The playoffs live on this tab too (YGO-28); there's nothing to show before the season is under way.
  const playoffs = useApi<Playoffs>(season.status === "ACTIVE" || season.status === "COMPLETE" ? `${seasonBase}/playoffs` : null);
  const location = useLocation();
  const [mineOnly, setMineOnly] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Selected | null>(null);
  const confirm = useConfirm();
  const po = playoffs.data && hasPlayoffsSection(playoffs.data) ? playoffs.data : null;
  // Arriving from the old Playoffs tab (…/schedule#playoffs): scroll there once it has loaded.
  useEffect(() => {
    if (location.hash === "#playoffs" && po) document.getElementById("playoffs")?.scrollIntoView();
  }, [location.hash, po]);

  if (schedule.error) return <p className="bad">Couldn't load the schedule ({schedule.error})</p>;
  if (!schedule.data) return <Loading />;
  const s = schedule.data;
  const zone = league.timeZone;

  const draw = async () => {
    if (s.generated && !(await confirm({ title: "Draw a new schedule?", message: "Every matchup changes.", confirmLabel: "Draw again" }))) return;
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

  // Before the playoffs start, the regular season comes first and the playoffs (seeding preview,
  // Start) follow; once they've started, or the season is over, the bracket and champion lead.
  const playoffsFirst = !!po && (po.started || po.complete);
  const playoffsBlock = po && (
    <section id="playoffs" className="stage">
      <h3 className="stage-head">{po.enabled ? "Playoffs" : "End of the season"}</h3>
      <PlayoffsSection playoffs={po} base={seasonBase} onChanged={() => { playoffs.reload(); schedule.reload(); }} />
    </section>
  );

  return (
    <>
      {playoffsFirst && playoffsBlock}
      <section className="stage">
        {po && (
          <div className="stage-head">
            <h3>Regular season</h3>
            {!playoffsFirst && po.enabled && <a href="#playoffs" className="muted">Playoffs ↓</a>}
          </div>
        )}
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
          <InfoTip>
            {s.generated ? "Until week 1 begins, you can draw new matchups; after that the schedule is fixed." : "A random draw of the players, recorded."}
          </InfoTip>
        </p>
      ) : s.generated && s.why && <p className="muted">{s.why}.</p>)}
      {error && <p className="bad">{error}</p>}

      {current && !over && (
        <section className="panel this-week">
          <h5>
            {s.currentWeek === 0 ? "First up: week 1" : `This week: week ${current.week}`}
            <span className="muted"> · {dates(current, zone)} · best of {current.bestOf}<Byes week={current} /></span>
          </h5>
          <Matchups week={current} where="current" selected={selected} onSelect={setSelected} />
          <Panel week={current} where="current" selected={selected} base={base.replace(/\/schedule$/, "")} onClose={() => setSelected(null)}
                 onDone={() => { setSelected(null); schedule.reload(); playoffs.reload(); }} />
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
                <Matchups week={w} compact where="all" selected={selected} onSelect={setSelected} />
                <Panel week={w} where="all" selected={selected} base={base.replace(/\/schedule$/, "")} onClose={() => setSelected(null)}
                       onDone={() => { setSelected(null); schedule.reload(); playoffs.reload(); }} />
              </li>
            ))}
          </ol>
        </>
      )}
      </section>
      {!playoffsFirst && playoffsBlock}
    </>
  );
}

// The week's matches; byes are named in the week's heading instead (see Byes). Each tile shows the
// score and where the result stands, and the actions the viewer has.
function Matchups({ week, compact = false, where, selected, onSelect }: {
  week: ScheduleWeek; compact?: boolean; where: Selected["where"]; selected: Selected | null; onSelect: (s: Selected) => void;
}) {
  const games = week.matches.filter((m) => m.b !== null);
  if (games.length === 0) return <p className="muted">{week.matches.length > 0 ? "You have a bye this week." : "No matches of yours this week."}</p>;
  return (
    <ul className={`matchups${compact ? " compact" : ""}`}>
      {games.map((m) => {
        const r = m.result;
        const counts = r && !r.forfeit;
        const isSelected = selected?.matchId === m.id && selected.where === where;
        return (
          <li key={m.id} className={`${m.mine ? "mine" : ""}${isSelected ? " selected" : ""}`}>
            <div className="matchup-line">
              <Player p={m.a} />
              <span className={`score${r?.status === "CONFIRMED" ? " final" : ""}`}>
                {counts ? `${r.gamesA} – ${r.gamesB}` : <span className="vs">vs</span>}
              </span>
              <Player p={m.b!} />
            </div>
            {r && r.games.length > 0 && <GameLinks games={r.games} />}
            <div className="matchup-status">
              <Status m={m} />
              <span className="matchup-actions">
                {m.canConfirm && <button className="primary" onClick={() => onSelect({ matchId: m.id, action: "confirm", where })}>Confirm…</button>}
                {m.canReport && !m.canConfirm && (
                  <button onClick={() => onSelect({ matchId: m.id, action: "report", where })}>{r?.status === "PENDING" ? "Change report" : "Report"}</button>
                )}
                {/* A commissioner playing in the match reports like any player; their commissioner
                    button only appears once there's a final or disputed result to correct. */}
                {m.canEnter && (!m.mine || r?.status === "CONFIRMED" || r?.status === "DISPUTED") && (
                  <button onClick={() => onSelect({ matchId: m.id, action: "enter", where })}>{r ? "Correct" : "Enter result"}</button>
                )}
              </span>
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function Status({ m }: { m: ScheduleMatch }) {
  const r = m.result;
  if (!r) return m.overdue ? <span className="badge status-open">Overdue · not reported</span> : <span className="muted">Not played yet</span>;
  if (r.forfeit) {
    const text = r.forfeit === "BOTH" ? "Both forfeit" : `${r.forfeit === "A" ? m.a.name : m.b!.name} forfeits`;
    return <span className="badge status-executed">{text}</span>;
  }
  const winner = r.gamesA > r.gamesB ? m.a.name : r.gamesB > r.gamesA ? m.b!.name : null;
  switch (r.status) {
    case "CONFIRMED":
      return <span className="badge status-executed">{winner ? `${winner} wins` : "Draw"}{r.autoConfirmed ? " · auto-confirmed" : r.byCommissioner ? " · by a commissioner" : ""}</span>;
    case "PENDING":
      return <span className="badge status-open">Reported by {r.reportedBy}, waiting for {r.reportedBy === m.a.name ? m.b!.name : m.a.name}</span>;
    case "DISPUTED":
      return <span className="badge ban-forbidden" title={r.disputeNote ?? undefined}>Disputed{r.disputeNote ? `: ${r.disputeNote}` : ""}</span>;
  }
}

// Under the tiles of the week holding the selected match: report or enter the games (and replay
// links, or a forfeit for commissioners), or confirm / dispute the opponent's report.
function Panel({ week, where, selected, base, onClose, onDone }: {
  week: ScheduleWeek; where: Selected["where"]; selected: Selected | null; base: string; onClose: () => void; onDone: () => void;
}) {
  const m = selected && selected.where === where ? week.matches.find((x) => x.id === selected.matchId) : undefined;
  if (!m || !m.b || !selected) return null;
  return <ResultForm key={`${m.id}-${selected.action}`} match={m} bestOf={week.bestOf} action={selected.action} base={base} onClose={onClose} onDone={onDone} />;
}

/** What the form needs from a match: regular (ScheduleMatch) or play-in / playoff (BracketMatch, then `bracket`). */
export interface FormMatch { id: string; a: SchedulePlayer; b: SchedulePlayer | null; result: ScheduleMatch["result"] }

// `bracket`: a play-in or playoff match, which needs a winner (no draws, no double forfeit).
export function ResultForm({ match: m, bestOf, action, base, onClose, onDone, bracket = false }: {
  match: FormMatch; bestOf: number; action: Action; base: string; onClose: () => void; onDone: () => void; bracket?: boolean;
}) {
  const r = m.result;
  const majority = Math.floor(bestOf / 2) + 1;
  const [a, setA] = useState(r && !r.forfeit ? r.gamesA : 0);
  const [b, setB] = useState(r && !r.forfeit ? r.gamesB : 0);
  const [forfeit, setForfeit] = useState<"" | "A" | "B" | "BOTH">("");
  // One row per possible game: a replay link and a note, prefilled from the current report.
  const [rows, setRows] = useState(() => Array.from({ length: bestOf }, (_, i) => {
    const g = r?.games.find((x) => x.game === i + 1);
    return { replay: g?.replay ?? "", note: g?.note ?? "" };
  }));
  const setRow = (i: number, field: "replay" | "note", value: string) =>
    setRows((x) => x.map((row, j) => (j === i ? { ...row, [field]: value } : row)));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async (path: string, body?: unknown) => {
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/matches/${m.id}/${path}`, body);
      onDone();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };
  const valid = forfeit !== "" || ((a > 0 || b > 0) && a <= majority && b <= majority && !(a === majority && b === majority) && a + b <= bestOf
    && !(bracket && a === b));
  const outcome = forfeit ? "" : a === b ? `a draw (${a}–${b})` : `${a > b ? m.a.name : m.b!.name} wins ${Math.max(a, b)}–${Math.min(a, b)}`;
  const options = Array.from({ length: majority + 1 }, (_, i) => i);

  if (action === "confirm" && r) {
    return (
      <div className="panel result-form">
        <p><strong>{r.reportedBy}</strong> reported <strong>{m.a.name} {r.gamesA} – {r.gamesB} {m.b!.name}</strong>. Is that right?</p>
        {r.games.length > 0 && <GameLinks games={r.games} open />}
        <p>
          <button className="primary" onClick={() => void send("confirm")} disabled={busy}>Yes, confirm</button>{" "}
          <button className="ghost" onClick={onClose} disabled={busy}>Cancel</button>
        </p>
        <p className="muted">If it's wrong, dispute it and a commissioner will settle it:</p>
        <div className="builder-send">
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="What happened? (optional)" maxLength={500} aria-label="Dispute note" />
          <button className="danger" onClick={() => void send("dispute", { note: note || null })} disabled={busy}>Dispute</button>
        </div>
        {error && <p className="bad">{error}</p>}
      </div>
    );
  }

  return (
    <div className="panel result-form">
      <p><strong>{action === "enter" ? (r ? "Correct the result" : "Enter the result") : "Report the result"}</strong>
        <span className="muted"> · best of {bestOf}</span>
        <InfoTip>Leave tied games out: enter only games someone won.{bracket ? "" : " Level games make the match a draw."}</InfoTip></p>
      {action === "enter" && (
        <div className="forfeit-choice">
          {(bracket ? (["", "A", "B"] as const) : (["", "A", "B", "BOTH"] as const)).map((f) => (
            <label key={f} className="check-inline">
              <input type="radio" name={`forfeit-${m.id}`} checked={forfeit === f} onChange={() => setForfeit(f)} />{" "}
              {f === "" ? "Games" : f === "BOTH" ? "Both forfeit" : `${f === "A" ? m.a.name : m.b!.name} forfeits`}
            </label>
          ))}
        </div>
      )}
      {forfeit === "" && (
        <>
          <div className="score-entry">
            <label>{m.a.name} <select value={a} onChange={(e) => setA(Number(e.target.value))}>{options.map((n) => <option key={n} value={n}>{n}</option>)}</select></label>
            <span className="muted">–</span>
            <label><select value={b} onChange={(e) => setB(Number(e.target.value))}>{options.map((n) => <option key={n} value={n}>{n}</option>)}</select> {m.b!.name}</label>
          </div>
          <p className={valid || (a === 0 && b === 0) ? "muted" : "bad"}>
            {valid ? `That's ${outcome}.` : a === 0 && b === 0 ? "Pick the games each player won."
              : bracket && a === b ? "A playoff match needs a winner." : `That doesn't fit a best of ${bestOf} (first to ${majority}).`}
          </p>
          <div className="game-rows">
            <span className="muted game-rows-head">Replays and notes, per game (optional)</span>
            {rows.map((row, i) => (
              <div key={i} className="game-row">
                <span className="game-label">Game {i + 1}</span>
                <input value={row.replay} placeholder="Replay link (https://…)" aria-label={`Game ${i + 1} replay link`} maxLength={300}
                       onChange={(e) => setRow(i, "replay", e.target.value)} />
                <input value={row.note} placeholder="Note" aria-label={`Game ${i + 1} note`} maxLength={200}
                       onChange={(e) => setRow(i, "note", e.target.value)} />
              </div>
            ))}
          </div>
        </>
      )}
      <p>
        <button className="primary" onClick={() => void send(action === "enter" ? "result" : "report", action === "enter" && forfeit
          ? { forfeit } : { gamesA: a, gamesB: b, replays: rows.map((x) => x.replay), notes: rows.map((x) => x.note) })} disabled={busy || !valid}>
          {action === "enter" ? "Save result" : r?.status === "PENDING" ? "Update report" : "Report"}
        </button>{" "}
        <button className="ghost" onClick={onClose} disabled={busy}>Cancel</button>
      </p>
      {action === "report" && <p className="muted">Your opponent confirms it (or it confirms itself after the season's delay).</p>}
      {error && <p className="bad">{error}</p>}
    </div>
  );
}

// The games' replay links and notes on a match tile: a one-line summary that opens into a row per
// game ("Game 1 · ▶ Replay · note"). `open` starts it expanded (the confirm panel).
export function GameLinks({ games, open = false }: { games: { game: number; replay: string | null; note: string | null }[]; open?: boolean }) {
  const replays = games.filter((g) => g.replay).length;
  const notes = games.filter((g) => g.note).length;
  const summary = [replays > 0 && `${replays} replay${replays === 1 ? "" : "s"}`, notes > 0 && `${notes} note${notes === 1 ? "" : "s"}`]
    .filter(Boolean).join(" · ");
  return (
    <details className="game-links" open={open}>
      <summary>{summary}</summary>
      <ul>
        {games.map((g) => (
          <li key={g.game}>
            <span className="game-label">Game {g.game}</span>
            {g.replay ? (
              <a href={g.replay} target="_blank" rel="noreferrer noopener" className="replay-link">▶ Replay</a>
            ) : (
              <span className="muted replay-link">no replay</span>
            )}
            {g.note && <span className="game-note">{g.note}</span>}
          </li>
        ))}
      </ul>
    </details>
  );
}

/** " · Duelist 3 has a bye" (odd player counts: one player sits each week out). */
function Byes({ week }: { week: ScheduleWeek }) {
  const byes = week.matches.filter((m) => m.b === null);
  if (byes.length === 0) return null;
  return <> · {byes.map((m) => (m.a.you ? "you have" : `${m.a.name} has`)).join(", ")} a bye</>;
}

export function Player({ p }: { p: SchedulePlayer }) {
  return (
    <span className="matchup-player">
      <PlayerAvatar name={p.name} passcode={p.avatar} size="sm" />
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
