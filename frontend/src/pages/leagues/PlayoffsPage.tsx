import { useState } from "react";
import PlayoffBracket from "./PlayoffBracket";
import { Navigate } from "react-router-dom";
import { apiPost, type BracketMatch, type Playoffs, type SchedulePlayer } from "../../api/client";
import { GameLinks, Player, ResultForm, type Action } from "./SchedulePage";
import { useSeason } from "./SeasonLayout";
import { useConfirm, type ConfirmOptions } from "../../components/Feedback";
import InfoTip from "../../components/InfoTip";

// The playoffs (YGO-27), shown as a section of the Schedule tab (YGO-28): the seeding (a preview
// from the standings until a commissioner starts the playoffs), the bracket as a tree (YGO-30) with
// the matches as tiles below it, by round, and the champion. Results are reported, confirmed and entered here like regular matches. Each match has
// a week from when both its players are known. Without playoffs, it holds the champion and the
// commissioners' "Complete the season".

/** The old Playoffs tab's URL, kept working: the playoffs now live on the Schedule tab. */
export default function PlayoffsRedirect() {
  return <Navigate to="../schedule#playoffs" replace />;
}

/** Whether the schedule shows a playoffs section at all: playoffs on, or a season to complete or completed. */
export const hasPlayoffsSection = (p: Playoffs) => p.enabled || p.complete || p.canComplete;

export function PlayoffsSection({ playoffs: p, base, onChanged }: { playoffs: Playoffs; base: string; onChanged: () => void }) {
  const { season, reload: reloadSeason } = useSeason();
  const [selected, setSelected] = useState<{ id: string; action: Action } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();

  const act = async (path: string, ask: ConfirmOptions) => {
    if (!(await confirm(ask))) return;
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/playoffs/${path}`);
      onChanged();
      reloadSeason();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  // Columns: play-in rounds, then bracket rounds, each under its own heading.
  const columns: { key: string; title: string; matches: BracketMatch[] }[] = [];
  for (const m of p.matches) {
    const key = `${m.stage}-${m.round}`;
    let col = columns.find((c) => c.key === key);
    if (!col) {
      const plural = m.label === "Final" || m.label.startsWith("Round of") ? m.label : `${m.label}s`;   // "Semifinals", but "Final"
      col = { key, title: m.stage === "PLAY_IN" ? `Play-in${p.settings.playInTeams === 4 ? `, round ${m.round}` : ""}` : plural, matches: [] };
      columns.push(col);
    }
    col.matches.push(m);
  }
  const chosen = selected ? p.matches.find((m) => m.id === selected.id) : undefined;
  // From the tree: scroll to the match's tile and flash it.
  const [flash, setFlash] = useState<string | null>(null);
  const open = (id: string) => {
    document.getElementById(`match-${id}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
    setFlash(id);
    window.setTimeout(() => setFlash((f) => (f === id ? null : f)), 1600);
  };

  return (
    <>
      {p.champion && (
        <p className="panel champion">
          🏆 <strong>{p.champion.you ? "You" : p.champion.name}</strong> {p.enabled ? "won the final" : "topped the standings"} and{" "}
          {p.champion.you ? "are" : "is"} the {season.name} champion{p.champion.you ? "!" : "."}
        </p>
      )}

      {!p.enabled ? (
        <>
          {!p.complete && <p className="muted">No playoffs this season: the regular-season standings decide the champion.</p>}
          {!p.complete && p.canComplete && (
            <p><button className="primary" disabled={busy} onClick={() => void act("complete", { title: "Complete the season?", message: "The top of the standings becomes champion, and results are locked.", confirmLabel: "Complete the season" })}>Complete the season</button></p>
          )}
          {!p.complete && !p.canComplete && p.why && <p className="muted">{p.why}.</p>}
        </>
      ) : (
        <>
          {/* The shape in words, the best-of as pills, and the timing rule in the tip (YGO-36). */}
          <p className="schedule-summary">
            <span className="muted">{p.shape}</span>
            <span className="pill">Best of {p.playoffBestOf}</span>
            {(p.settings.playInTeams ?? 0) > 0 && p.playInBestOf !== p.playoffBestOf && <span className="pill">Play-in best of {p.playInBestOf}</span>}
            <InfoTip>Each match has a week from when both its players are known; finishing sooner moves things on.</InfoTip>
          </p>
          {!p.started && (
            <>
              {/* Commissioners always see the button (p.why is only sent to them), disabled with the
                  reason until the regular season is final, so it's clear where starting happens. */}
              {(p.canStart || p.why) && (
                <p className="start-playoffs">
                  <button className="primary" disabled={busy || !p.canStart} onClick={() => void act("start", { title: "Start the playoffs?", message: "Seeding is fixed from the standings now, and trades and free agency close.", confirmLabel: "Start the playoffs" })}>
                    Start the playoffs
                  </button>{" "}
                  {p.canStart
                    ? <span className="muted">Seeding is fixed from the standings, and trades and free agency close.</span>
                    : <span className="warn-text">Not yet: {p.why!.charAt(0).toLowerCase() + p.why!.slice(1)}.{p.why!.includes("final result") && " A reported result becomes final when the opponent confirms it, or on its own after the season's confirm delay."}</span>}
                </p>
              )}
            </>
          )}
          {error && <p className="bad">{error}</p>}

          {p.seeds.length > 0 && (
            <details className="seeds" open={!p.started}>
              <summary>{p.started ? "Seeds" : "Seeding if the playoffs started now"}</summary>
              <ol>
                {p.seeds.map((s) => (
                  <li key={s.seed}>
                    <Player p={s.player} />{s.playIn && <span className="badge">play-in</span>}
                  </li>
                ))}
              </ol>
            </details>
          )}

          {p.matches.length > 0 && <PlayoffBracket matches={p.matches} onOpen={open} />}
          {!p.started && (p.preview?.length ?? 0) > 0 && (
            <>
              <h5 className="preview-head">The bracket if the playoffs started now</h5>
              <PlayoffBracket matches={p.preview!} onOpen={null} />
            </>
          )}
          {/* The matches as regular-season tiles, by round; the result form opens under the round
              holding the chosen match, as it does under a week. */}
          {columns.map((c) => (
            <section key={c.key} className="bracket-round">
              <h5>{c.title} <span className="muted">· best of {c.matches[0].bestOf}</span></h5>
              <ul className="matchups">
                {c.matches.map((m) => (
                  <MatchTile key={m.id} m={m} showLabel={c.matches.length > 1} selected={selected?.id === m.id} flash={flash === m.id}
                             onSelect={(action) => setSelected({ id: m.id, action })} />
                ))}
              </ul>
              {chosen && chosen.a && chosen.b && selected && c.matches.some((m) => m.id === chosen.id) && (
                <ResultForm key={`${chosen.id}-${selected.action}`} match={{ id: chosen.id, a: chosen.a, b: chosen.b, result: chosen.result }}
                            bestOf={chosen.bestOf} action={selected.action} base={base} bracket onClose={() => setSelected(null)}
                            onDone={() => { setSelected(null); onChanged(); reloadSeason(); }} />
              )}
            </section>
          ))}
        </>
      )}
    </>
  );
}

// A play-in or playoff match as a regular-season tile (YGO-30): "Player 2 – 1 Player" with each
// player's seed beside them, the result's status and the viewer's actions. Before its players are
// known, each side says where they come from ("Winner of Semifinal").
function MatchTile({ m, showLabel, selected, flash, onSelect }: {
  m: BracketMatch; showLabel: boolean; selected: boolean; flash: boolean; onSelect: (a: Action) => void;
}) {
  const r = m.result;
  const final = r?.status === "CONFIRMED";
  const counts = r && !r.forfeit;
  const side = (p: SchedulePlayer | null, seed: number | null, from: string | null) => (
    <span className={`bracket-player${final && m.winner && p && m.winner.memberId === p.memberId ? " won" : ""}`}>
      {seed != null && <span className="seed-tag" title={`Seed ${seed}`}>{seed}</span>}
      {p ? <Player p={p} /> : <span className="muted" title={from ?? undefined}>{shortFrom(from)}</span>}
    </span>
  );
  return (
    <li id={`match-${m.id}`} className={`${m.mine ? "mine" : ""}${selected ? " selected" : ""}${flash ? " flash" : ""}`}>
      {showLabel && <span className="matchup-label">{m.label}</span>}
      <div className="matchup-line">
        {side(m.a, m.seedA, m.aFrom)}
        <span className={`score${final ? " final" : ""}`}>
          {counts ? `${r.gamesA} – ${r.gamesB}` : <span className="vs">vs</span>}
        </span>
        {side(m.b, m.seedB, m.bFrom)}
      </div>
      {r && r.games.length > 0 && <GameLinks games={r.games} />}
      <div className="matchup-status">
        <BracketStatus m={m} />
        <span className="matchup-actions">
          {m.canConfirm && <button className="primary" onClick={() => onSelect("confirm")}>Confirm…</button>}
          {m.canReport && !m.canConfirm && <button onClick={() => onSelect("report")}>{r?.status === "PENDING" ? "Change report" : "Report"}</button>}
          {m.canEnter && (!m.mine || final || r?.status === "DISPUTED") && <button onClick={() => onSelect("enter")}>{r ? "Correct" : "Enter result"}</button>}
        </span>
      </div>
    </li>
  );
}

/** "Winner of Play-in: 2 v 3" → "Play-in winner", "Loser of Semifinal" → "Semifinal loser"; seeds as they are. */
function shortFrom(from: string | null): string {
  const m = from?.match(/^(Winner|Loser) of (.+)$/);
  if (!m) return from ?? "TBD";
  return `${m[2].replace(/:.*$/, "")} ${m[1].toLowerCase()}`;
}

function BracketStatus({ m }: { m: BracketMatch }) {
  const r = m.result;
  if (!m.a || !m.b) return <span className="muted">Waiting for earlier results</span>;
  if (r?.status === "CONFIRMED") {
    const how = r.forfeit ? " · by forfeit" : r.autoConfirmed ? " · auto-confirmed" : r.byCommissioner ? " · by a commissioner" : "";
    return <span className="badge status-executed">{m.winner?.name} advances{how}</span>;
  }
  if (r?.status === "PENDING") {
    const other = r.reportedBy === m.a.name ? m.b.name : m.a.name;
    return <span className="badge status-open">Reported by {r.reportedBy}, waiting for {other}</span>;
  }
  if (r?.status === "DISPUTED") return <span className="badge ban-forbidden" title={r.disputeNote ?? undefined}>Disputed{r.disputeNote ? `: ${r.disputeNote}` : ""}</span>;
  const due = m.deadline ? new Date(m.deadline).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" }) : "";
  return m.overdue ? <span className="badge status-open">Overdue · was due {due}</span> : <span className="muted">Play by {due}</span>;
}
