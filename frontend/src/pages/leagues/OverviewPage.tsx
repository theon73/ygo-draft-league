import { Link, useNavigate } from "react-router-dom";
import type {
  DraftState, NeedsYouItem, Playoffs, Schedule, SeasonRules, SeasonStandings, SeasonTransaction,
} from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { Loading } from "../../components/Feedback";
import NeedsYou from "../../components/NeedsYou";
import PlayerAvatar from "../../components/PlayerAvatar";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import PlayoffBracket from "./PlayoffBracket";
import { Matchups } from "./SchedulePage";
import { DraftSummary, RecentPicks } from "./SeasonDraftPage";
import { useSeason } from "./SeasonLayout";

// The season's Overview tab (YGO-31): where a season opens. What's waiting on you first, then
// what matters for the season's state:
//   setup     - commissioners: what's left before the draft; everyone: a preview of the season
//   drafting  - the draft at a glance and the latest picks
//   active    - this week's matches (or the playoff bracket), a standings snippet, recent pickups
//   complete  - the champion, the final standings and the bracket
// Each part links to the tab with the full picture. It reads the same endpoints as those tabs.

export default function OverviewPage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const needs = useApi<NeedsYouItem[]>(`${base}/needs-you`, String(draftVersion));

  return (
    <div className="overview">
      {needs.data && needs.data.length > 0 && (
        <section className="overview-section">
          <h4>Needs you</h4>
          <NeedsYou items={needs.data} />
        </section>
      )}
      {season.status === "SETUP" && <SetupOverview base={base} />}
      {season.status === "DRAFTING" && <DraftingOverview base={base} />}
      {(season.status === "ACTIVE" || season.status === "COMPLETE") && <SeasonOverview base={base} />}
    </div>
  );
}

// ---- Setup ----------------------------------------------------------------------------------

/** Where each readiness item is fixed. */
const READINESS_TAB: Record<string, string> = { players: "players", rounds: "draft", pool: "pool", "enough cards": "pool", calendar: "rules" };

function SetupOverview({ base }: { base: string }) {
  const { season, draftVersion } = useSeason();
  const draft = useApi<DraftState>(`${base}/draft`, String(draftVersion));
  const rules = useApi<SeasonRules>(`${base}/rules`);
  if (!draft.data || !rules.data) return <Loading />;
  const d = draft.data;
  const r = rules.data;
  const done = d.readiness.filter((c) => c.ok).length;

  return (
    <>
      {season.canManage && (
        <section className="overview-section">
          <div className="section-head">
            <h4>Before the draft</h4>
            <span className="muted small">{done} of {d.readiness.length} ready</span>
          </div>
          <ul className="setup-steps">
            {d.readiness.map((c) => (
              <li key={c.item} className={c.ok ? "ok" : undefined}>
                <span className="step-mark" aria-hidden="true">{c.ok ? "✓" : ""}</span>
                <span className="grow">{c.detail}</span>
                {!c.ok && READINESS_TAB[c.item] && <Link className="button sm" to={`../${READINESS_TAB[c.item]}`}>Fix</Link>}
              </li>
            ))}
          </ul>
          <p className="muted small">
            Seats are set on the <Link to="../players">Players</Link> tab (drawn at random or by hand), and the draft is started
            from the <Link to="../draft">Draft</Link> tab. Everything stays editable until then.
          </p>
        </section>
      )}

      <section className="overview-section">
        <h4>The season ahead</h4>
        <div className="stat-tiles">
          <div className="stat-tile">
            <div className="stat-label">Cards</div>
            <div className="stat-value">{r.format.tcgCutoff ? `Up to ${fmtDate(r.format.tcgCutoff)}` : "Not chosen"}</div>
            <div className="stat-sub">{r.format.banList ? r.format.banList.name : "no ban list"}</div>
          </div>
          <div className="stat-tile">
            <div className="stat-label">Draft</div>
            <div className="stat-value">{d.scheduledStart ? fmtWhen(d.scheduledStart, d.timeZone) : "Not scheduled"}</div>
            <div className="stat-sub">{d.order === "SNAKE" ? "Snake" : "Linear"}{d.roundCount ? ` · ${d.roundCount} rounds` : ""}</div>
          </div>
          <div className="stat-tile">
            <div className="stat-label">Season</div>
            <div className="stat-value">{r.calendar.startDate ? `From ${fmtDate(r.calendar.startDate)}` : "Dates not set"}</div>
            <div className="stat-sub">
              {r.competition.format === "ROUND_ROBIN" ? "Round-robin" : "Format not chosen"} · best of {r.competition.bestOf}
              {r.weeks.length > 0 && ` · ${r.weeks.length} weeks`}
            </div>
          </div>
        </div>
        <h5>Players ({season.participants.length})</h5>
        {season.participants.length === 0 ? (
          <p className="muted">Nobody has been added yet.</p>
        ) : (
          <ul className="player-chips">
            {season.participants.map((p) => (
              <li key={p.memberId} className={p.you ? "you" : undefined}>
                <PlayerAvatar name={p.displayName} passcode={p.avatar} size="md" />
                <span>
                  {p.displayName}{p.you && <span className="badge">you</span>}
                  {p.teamName && <span className="muted small"><br />{p.teamName}</span>}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

// ---- Drafting -------------------------------------------------------------------------------

function DraftingOverview({ base }: { base: string }) {
  const { draftVersion } = useSeason();
  const draft = useApi<DraftState>(`${base}/draft`, String(draftVersion));
  if (!draft.data) return <Loading />;
  const d = draft.data;
  const c = d.onTheClock;
  const mine = !d.held && c && d.mySeat === c.seat;
  return (
    <section className="overview-section">
      <div className={`callout${mine ? " mine" : ""}`}>
        <span className="grow">
          {d.held ? <><strong>The draft is paused.</strong>{c && ` ${c.displayName} is up next.`}</>
            : c ? <>{mine ? <strong>You're on the clock.</strong> : <><strong>{c.displayName}</strong> is on the clock</>} · pick #{c.overallPick}, round {c.round}</>
            : "The draft is under way."}
        </span>
        <Link className={`button sm${mine ? " primary" : ""}`} to="../draft">Go to the draft</Link>
      </div>
      <DraftSummary draft={d} />
      <RecentPicks draft={d} />
    </section>
  );
}

// ---- Active / complete ----------------------------------------------------------------------

function SeasonOverview({ base }: { base: string }) {
  const { season } = useSeason();
  const navigate = useNavigate();
  const schedule = useApi<Schedule>(`${base}/schedule`);
  const playoffs = useApi<Playoffs>(`${base}/playoffs`);
  const standings = useApi<SeasonStandings>(`${base}/standings`);
  const feed = useApi<SeasonTransaction[]>(`${base}/transactions`);
  if (!schedule.data || !playoffs.data || !standings.data) return <Loading />;
  const s = schedule.data;
  const p = playoffs.data;
  const complete = season.status === "COMPLETE";

  // This week's matches (yours first); before week 1, week 1's.
  const week = s.weeks.find((w) => w.current) ?? (s.currentWeek === 0 ? s.weeks[0] : undefined);
  const thisWeek = week && { ...week, matches: [...week.matches].sort((a, b) => Number(b.mine) - Number(a.mine)) };
  const bracket = p.started && p.matches.length > 0;

  return (
    <>
      {p.champion && (
        <p className="panel champion overview-champion">
          <PlayerAvatar name={p.champion.name} passcode={p.champion.avatar} size="lg" />
          <span>
            <span className="eyebrow">{season.name} champion</span>
            <strong>{p.champion.you ? "You!" : p.champion.name}</strong>
            {p.champion.teamName && <span className="muted"> · {p.champion.teamName}</span>}
          </span>
        </p>
      )}

      {bracket ? (
        <section className="overview-section">
          <div className="section-head">
            <h4>Playoffs</h4>
            <Link className="button sm" to="../schedule#playoffs">All playoff matches</Link>
          </div>
          <PlayoffBracket matches={p.matches} onOpen={() => navigate("../schedule#playoffs")} />
        </section>
      ) : !complete && thisWeek ? (
        <section className="overview-section">
          <div className="section-head">
            <h4>{s.currentWeek === 0 ? `First up: week ${thisWeek.week}` : `This week · week ${thisWeek.week}`}</h4>
            <span className="muted small">best of {thisWeek.bestOf}</span>
            <Link className="button sm" to="../schedule">Full schedule</Link>
          </div>
          <Matchups week={thisWeek} where="current" selected={null} />
        </section>
      ) : !complete && s.generated && s.currentWeek > s.weeks.length ? (
        <section className="overview-section">
          <h4>The regular season is over</h4>
          <p className="muted">{p.enabled ? "The playoffs come next." : "A commissioner completes the season once every result is final."}</p>
          <Link className="button sm" to="../schedule#playoffs">{p.enabled ? "Playoffs" : "Schedule"}</Link>
        </section>
      ) : null}

      <div className="overview-grid">
        <section className="overview-section">
          <div className="section-head">
            <h4>{complete ? "Final standings" : "Standings"}</h4>
            <Link className="button sm" to="../standings">Full table</Link>
          </div>
          <StandingsSnippet standings={standings.data} />
        </section>
        <section className="overview-section">
          <div className="section-head">
            <h4>Recent pickups</h4>
            <Link className="button sm" to="../transactions">All transactions</Link>
          </div>
          <RecentPickups feed={feed.data ?? []} />
        </section>
      </div>
    </>
  );
}

/** The top of the table, plus your row if it's further down. */
function StandingsSnippet({ standings }: { standings: SeasonStandings }) {
  const top = standings.rows.slice(0, 4);
  const me = standings.rows.find((r) => r.you);
  const rows = me && !top.includes(me) ? [...top, me] : top;
  if (rows.length === 0) return <p className="muted">No players yet.</p>;
  return (
    <table className="standings-snippet">
      <thead><tr><th>#</th><th>Player</th><th>W–L</th><th title="Game difference">+/−</th></tr></thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={r.memberId} className={`${r.you ? "mine" : ""}${me && r === me && i === rows.length - 1 && !top.includes(me) ? " gap-before" : ""}`}>
            <td>{r.rank}</td>
            <td className="standings-player">
              <PlayerAvatar name={r.name} passcode={r.avatar} size="sm" />
              <Link to={`../rosters/${r.memberId}`}>{r.name}</Link>{r.you && <span className="badge">you</span>}
            </td>
            <td>{r.wins}–{r.losses}{standings.showDraws ? `–${r.draws}` : ""}</td>
            <td>{r.gameDiff > 0 ? `+${r.gameDiff}` : r.gameDiff}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The latest claims and trades as card-art tiles: the card picked up, and who took it how. */
function RecentPickups({ feed }: { feed: SeasonTransaction[] }) {
  const recent = [...feed].sort((a, b) => b.at.localeCompare(a.at)).slice(0, 6);
  if (recent.length === 0) return <p className="muted">No claims or trades yet.</p>;
  return (
    <ul className="pickups">
      {recent.map((t, i) => {
        const card = t.added[0];
        return (
          <li key={`${t.at}-${i}`}>
            {card ? (
              <CardPreview cardId={card.cardId}><CardImage passcode={card.passcode} size="small" alt="" className="pickup-art" /></CardPreview>
            ) : <span className="pickup-art card-image" />}
            <span className="pickup-text">
              <span className="pickup-name" title={card?.name}>{card?.name ?? "—"}{t.added.length > 1 && <span className="muted"> +{t.added.length - 1}</span>}</span>
              <span className="muted small">
                {t.player} · {t.kind === "FREE_AGENCY" ? "FA claim" : `trade with ${t.counterparty}`}
              </span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function fmtDate(iso: string): string {
  return new Date(`${iso}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

function fmtWhen(instant: string, zone: string): string {
  return new Date(instant).toLocaleString(undefined, { timeZone: zone, month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}
