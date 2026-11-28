import InfoTip from "../../components/InfoTip";
import { Link } from "react-router-dom";
import type { Playoffs, Schedule, SeasonStandings, Tiebreaker } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import PlayerAvatar from "../../components/PlayerAvatar";
import { Loading } from "../../components/Feedback";

// The Standings tab (YGO-26; pass in YGO-36): the regular season's table from final results, with
// each player's recent form and, when the season has playoffs, where they'd land (or did). A draws
// column appears only once a match has been drawn.
const TIEBREAKER: Record<Tiebreaker, string> = {
  GAME_DIFF: "game difference",
  GAMES_WON: "games won",
  HEAD_TO_HEAD: "head-to-head",
};

type Outcome = "W" | "L" | "D";
interface FormEntry { outcome: Outcome; label: string }

const FORM_LENGTH = 5;

/** Each player's final regular-season results, oldest first, from the schedule. */
function formByPlayer(schedule: Schedule | null): Map<string, FormEntry[]> {
  const out = new Map<string, FormEntry[]>();
  if (!schedule) return out;
  const add = (id: string, e: FormEntry) => out.set(id, [...(out.get(id) ?? []), e]);
  for (const w of schedule.weeks) {
    for (const m of w.matches) {
      const r = m.result;
      if (!m.b || !r || r.status !== "CONFIRMED") continue;
      // A forfeit is a loss for whoever forfeited (both, for a double forfeit) and a win for the other.
      const aWins = r.forfeit ? r.forfeit === "B" : r.gamesA > r.gamesB;
      const bWins = r.forfeit ? r.forfeit === "A" : r.gamesB > r.gamesA;
      const score = r.forfeit ? "by forfeit" : `${r.gamesA}–${r.gamesB}`;
      const how = (o: Outcome, other: string, s: string) =>
        `Week ${w.week}: ${o === "W" ? "beat" : o === "L" ? "lost to" : "drew with"} ${other} ${s}`;
      const oa: Outcome = aWins ? "W" : bWins || r.forfeit === "BOTH" ? "L" : "D";
      const ob: Outcome = bWins ? "W" : aWins || r.forfeit === "BOTH" ? "L" : "D";
      add(m.a.memberId, { outcome: oa, label: how(oa, m.b.name, score) });
      add(m.b.memberId, { outcome: ob, label: how(ob, m.a.name, r.forfeit ? score : `${r.gamesB}–${r.gamesA}`) });
    }
  }
  return out;
}

export default function StandingsPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const standings = useApi<SeasonStandings>(`${base}/standings`);
  // For the form column and the playoff picture; the table shows without them.
  const schedule = useApi<Schedule>(`${base}/schedule`);
  const playoffs = useApi<Playoffs>(season.status === "ACTIVE" || season.status === "COMPLETE" ? `${base}/playoffs` : null);

  if (standings.error) return <p className="bad">Couldn't load the standings ({standings.error})</p>;
  if (!standings.data) return <Loading />;
  const s = standings.data;
  const signed = (n: number) => (n > 0 ? `+${n}` : String(n));
  const form = formByPlayer(schedule.data);
  const showForm = form.size > 0;
  const po = playoffs.data?.enabled ? playoffs.data : null;
  const seedOf = new Map(po?.seeds.map((x) => [x.player.memberId, x]) ?? []);

  return (
    <>
      <p className="standings-summary">
        <span className="muted">Ranked by wins</span>
        <InfoTip>
          Ties are broken by {s.tiebreakers.map((t) => TIEBREAKER[t]).join(", then ")}. Only final results count: a reported result
          becomes final when the opponent confirms it, or on its own after the season's delay. Results are reported on
          the <Link to="../schedule">Schedule</Link> tab.
        </InfoTip>
        {s.remaining > 0 && <span className="pill">{s.remaining} match{s.remaining === 1 ? "" : "es"} to go</span>}
        {s.waiting > 0 && <span className="pill accent">{s.waiting} waiting for confirmation</span>}
        {s.remaining === 0 && s.waiting === 0 && s.rows.some((r) => r.played > 0) && <span className="pill">Final</span>}
      </p>
      {s.rows.length === 0 ? (
        <p className="muted">Nobody is playing this season yet.</p>
      ) : (
        <div className="table-scroll">
          <table className="standings">
            <thead>
              <tr>
                <th>#</th><th>Player</th><th title="Matches played" className="hide-phone">P</th><th title="Wins">W</th><th title="Losses">L</th>
                {s.showDraws && <th title="Draws">D</th>}
                <th title="Games won – games lost" className="hide-phone">Games</th><th title="Game difference">+/−</th>
                {showForm && <th title={`The last ${FORM_LENGTH} results, latest on the right`} className="form-col">Form</th>}
                {po && <th title={po.started ? "Seed in the playoffs" : "Where they'd be seeded if the playoffs started now"}>Playoffs</th>}
              </tr>
            </thead>
            <tbody>
              {s.rows.map((r, i) => {
                const seed = seedOf.get(r.memberId);
                // A line under the last player who'd make it (direct, or the play-in).
                const next = s.rows[i + 1];
                const cut = po && seed && next && !seedOf.has(next.memberId);
                return (
                  <tr key={r.memberId} className={[r.you ? "mine" : "", cut ? "cut" : ""].filter(Boolean).join(" ") || undefined}>
                    <td>{r.rank}</td>
                    <td className="standings-player">
                      <PlayerAvatar name={r.name} passcode={r.avatar} size="sm" />
                      <Link to={`../rosters/${r.memberId}`}>{r.name}</Link>{r.you && <span className="badge">you</span>}
                      {r.teamName && <span className="muted"> {r.teamName}</span>}
                    </td>
                    <td className="hide-phone">{r.played}</td><td><strong>{r.wins}</strong></td><td>{r.losses}</td>
                    {s.showDraws && <td>{r.draws}</td>}
                    <td className="hide-phone">{r.gamesWon}–{r.gamesLost}</td><td>{signed(r.gameDiff)}</td>
                    {showForm && <td className="form-col"><Form entries={(form.get(r.memberId) ?? []).slice(-FORM_LENGTH)} /></td>}
                    {po && (
                      <td className="seed-col">
                        {seed ? (
                          <span className={`badge${seed.playIn ? " neutral" : " status-executed"}`}>
                            {seed.playIn ? `Play-in · ${seed.seed}` : `Seed ${seed.seed}`}
                          </span>
                        ) : <span className="muted">—</span>}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {po && !po.started && seedOf.size > 0 && (
        <p className="muted small">
          Playoff spots are a preview from today's standings <InfoTip>{po.shape}</InfoTip>; they're fixed when a commissioner starts the playoffs.
        </p>
      )}
    </>
  );
}

/** Up to five little W / L / D squares, latest on the right; hover one for the match. */
function Form({ entries }: { entries: FormEntry[] }) {
  if (entries.length === 0) return <span className="muted">—</span>;
  return (
    <span className="form" aria-label={entries.map((e) => e.outcome).join(" ")}>
      {entries.map((e, i) => <span key={i} className={`form-${e.outcome}`} title={e.label}>{e.outcome}</span>)}
    </span>
  );
}
