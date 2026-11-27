import { useState } from "react";
import { apiPost, type BracketMatch, type Playoffs, type SchedulePlayer } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { GameLinks, Player, ResultForm, type Action } from "./SchedulePage";
import { useSeason } from "./SeasonLayout";

// The Playoffs tab (YGO-27): the seeding (a preview from the standings until a commissioner starts
// the playoffs), the bracket by round, and the champion. Results are reported, confirmed and entered
// here like regular matches. Each match has a week from when both its players are known.
export default function PlayoffsPage() {
  const { league } = useLeague();
  const { season, reload: reloadSeason } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const playoffs = useApi<Playoffs>(`${base}/playoffs`);
  const [selected, setSelected] = useState<{ id: string; action: Action } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (playoffs.error) return <p className="bad">Couldn't load the playoffs ({playoffs.error})</p>;
  if (!playoffs.data) return <p className="muted">Loading…</p>;
  const p = playoffs.data;

  const act = async (path: string, confirm: string) => {
    if (!window.confirm(confirm)) return;
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${base}/playoffs/${path}`);
      playoffs.reload();
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
      <h4>Playoffs</h4>
      {p.champion && (
        <p className="panel champion">
          🏆 <strong>{p.champion.you ? "You" : p.champion.name}</strong> {p.enabled ? "won the final" : "topped the standings"} and{" "}
          {p.champion.you ? "are" : "is"} the {season.name} champion{p.champion.you ? "!" : "."}
        </p>
      )}

      {!p.enabled ? (
        <>
          <p className="muted">No playoffs this season: the regular-season standings decide the champion. (Playoffs are set up on the Rules tab.)</p>
          {!p.complete && p.canComplete && (
            <p><button disabled={busy} onClick={() => void act("complete", "Complete the season? The top of the standings becomes champion, and results are locked.")}>Complete the season</button></p>
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
                  <button disabled={busy} onClick={() => void act("start", "Start the playoffs? Seeding is fixed from the standings now, and trades and free agency close.")}>
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
                        onDone={() => { setSelected(null); playoffs.reload(); reloadSeason(); }} />
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
          {m.canConfirm && <button onClick={() => onSelect("confirm")}>Confirm…</button>}
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
