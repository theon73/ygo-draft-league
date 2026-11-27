import { useState } from "react";
import { Navigate } from "react-router-dom";
import { apiPost, type BracketMatch, type Playoffs, type SchedulePlayer } from "../../api/client";
import { GameLinks, Player, ResultForm, type Action } from "./SchedulePage";
import { useSeason } from "./SeasonLayout";
import { useConfirm, type ConfirmOptions } from "../../components/Feedback";

// The playoffs (YGO-27), shown as a section of the Schedule tab (YGO-28): the seeding (a preview
// from the standings until a commissioner starts the playoffs), the bracket by round, and the
// champion. Results are reported, confirmed and entered here like regular matches. Each match has
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
          <p className="muted">
            {p.shape} Playoff matches are best of {p.playoffBestOf}
            {(p.settings.playInTeams ?? 0) > 0 && (p.playInBestOf === p.playoffBestOf ? ", the play-in too" : `; the play-in, best of ${p.playInBestOf}`)}.
            {" "}Each match has a week from when both players are known; finishing sooner moves things on.
          </p>
          {!p.started && (
            <>
              {p.canStart ? (
                <p>
                  <button className="primary" disabled={busy} onClick={() => void act("start", { title: "Start the playoffs?", message: "Seeding is fixed from the standings now, and trades and free agency close.", confirmLabel: "Start the playoffs" })}>
                    Start the playoffs
                  </button>{" "}
                  <span className="muted">Seeding is fixed from the standings, and trades and free agency close.</span>
                </p>
              ) : p.why && <p className="muted">{p.why}.</p>}
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

          {columns.length > 0 && (
            <div className="bracket">
              {columns.map((c) => (
                <section key={c.key} className="bracket-round">
                  <h5>{c.title}</h5>
                  {c.matches.map((m) => (
                    <MatchCard key={m.id} m={m} selected={selected?.id === m.id} onSelect={(action) => setSelected({ id: m.id, action })} />
                  ))}
                </section>
              ))}
            </div>
          )}
          {chosen && chosen.a && chosen.b && selected && (
            <ResultForm key={`${chosen.id}-${selected.action}`} match={{ id: chosen.id, a: chosen.a, b: chosen.b, result: chosen.result }}
                        bestOf={chosen.bestOf} action={selected.action} base={base} bracket onClose={() => setSelected(null)}
                        onDone={() => { setSelected(null); onChanged(); reloadSeason(); }} />
          )}
        </>
      )}
    </>
  );
}

function MatchCard({ m, selected, onSelect }: { m: BracketMatch; selected: boolean; onSelect: (a: Action) => void }) {
  const r = m.result;
  const final = r?.status === "CONFIRMED";
  const side = (p: SchedulePlayer | null, seed: number | null, from: string | null, games: number | undefined, forfeited: boolean) => (
    <div className={`bracket-side${final && m.winner && p && m.winner.memberId === p.memberId ? " won" : ""}`}>
      <span className="bracket-seed">{seed ?? ""}</span>
      {p ? <Player p={p} /> : <span className="muted">{from}</span>}
      <span className="bracket-games">{forfeited ? "FF" : r && !r.forfeit ? games : ""}</span>
    </div>
  );
  return (
    <div className={`bracket-match${m.mine ? " mine" : ""}${selected ? " selected" : ""}`}>
      <div className="bracket-label">{m.label} · Bo{m.bestOf}</div>
      {side(m.a, m.seedA, m.aFrom, r?.gamesA, r?.forfeit === "A")}
      {side(m.b, m.seedB, m.bFrom, r?.gamesB, r?.forfeit === "B")}
      {r && r.games.length > 0 && <GameLinks games={r.games} />}
      <div className="matchup-status">
        <BracketStatus m={m} />
        <span className="matchup-actions">
          {m.canConfirm && <button className="primary" onClick={() => onSelect("confirm")}>Confirm…</button>}
          {m.canReport && !m.canConfirm && <button onClick={() => onSelect("report")}>{r?.status === "PENDING" ? "Change report" : "Report"}</button>}
          {m.canEnter && (!m.mine || final || r?.status === "DISPUTED") && <button onClick={() => onSelect("enter")}>{r ? "Correct" : "Enter result"}</button>}
        </span>
      </div>
    </div>
  );
}

function BracketStatus({ m }: { m: BracketMatch }) {
  const r = m.result;
  if (!m.a || !m.b) return <span className="muted">Waiting for earlier results</span>;
  if (r?.status === "CONFIRMED") return <span className="badge status-executed">{m.winner?.name} advances{r.autoConfirmed ? " · auto-confirmed" : ""}</span>;
  if (r?.status === "PENDING") return <span className="badge status-open">Reported by {r.reportedBy}, waiting for confirmation</span>;
  if (r?.status === "DISPUTED") return <span className="badge ban-forbidden" title={r.disputeNote ?? undefined}>Disputed</span>;
  const due = m.deadline ? new Date(m.deadline).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" }) : "";
  return m.overdue ? <span className="badge status-open">Overdue · was due {due}</span> : <span className="muted">Play by {due}</span>;
}
