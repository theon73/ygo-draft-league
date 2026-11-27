import PlayerAvatar from "../../components/PlayerAvatar";
import { Link, NavLink, useNavigate } from "react-router-dom";
import type { PoolCard, Roster, RosterCard, RosterGroup, Rosters } from "../../api/client";
import CardPreview from "../../components/CardPreview";
import { useApi } from "../../hooks/useApi";
import { statusLabel } from "../explore/banStatus";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import { Loading } from "../../components/Feedback";

// The Rosters tab: every player's cards side by side. One player's roster (with card art) is
// RosterPage, the free agents FreeAgentsPage; all three share RosterNav at the top.

export const GROUPS: { group: RosterGroup; label: string }[] = [
  { group: "MONSTER", label: "Monsters" },
  { group: "EXTRA", label: "Extra Deck" },
  { group: "SPELL", label: "Spells" },
  { group: "TRAP", label: "Traps" },
];

/** Copies a player may put in a deck: none if soft-banned, else the league's or the ban list's limit. */
export const playable = (c: PoolCard) => (c.softBanned ? 0 : c.effectiveMax);

export function countsLine(r: Roster): string {
  const c = r.counts;
  return `${c.total} card${c.total === 1 ? "" : "s"} · ${c.monsters} monsters · ${c.extra} extra · ${c.spells} spells · ${c.traps} traps`;
}

/** Fetches the rosters, refetching whenever the live draft moves (picks land on rosters as they're made). */
export function useRosters() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  return useApi<Rosters>(`/leagues/${league.id}/seasons/${season.id}/rosters`, String(draftVersion));
}

/** All rosters, a player chooser and the free agents, above every roster page. */
export function RosterNav({ rosters, current }: { rosters: Rosters | null; current?: string }) {
  const { league } = useLeague();
  const { season } = useSeason();
  const navigate = useNavigate();
  const base = `/leagues/${league.id}/seasons/${season.id}/rosters`;
  return (
    <div className="pool-toolbar">
      <nav className="tabs small" aria-label="Rosters">
        <NavLink to={base} end className={({ isActive }) => (isActive ? "active" : "")}>All rosters</NavLink>
        <NavLink to={`${base}/free-agents`} className={({ isActive }) => (isActive ? "active" : "")}>
          Free agents{rosters ? ` (${rosters.freeAgents.toLocaleString()})` : ""}
        </NavLink>
      </nav>
      {rosters && rosters.players.length > 0 && (
        <select aria-label="Show a player's roster" value={current ?? ""} onChange={(e) => e.target.value && navigate(`${base}/${e.target.value}`)}>
          <option value="">A player's roster…</option>
          {rosters.players.map((p) => (
            <option key={p.memberId} value={p.memberId}>{p.displayName}{p.teamName ? ` (${p.teamName})` : ""}{p.you ? " · you" : ""}</option>
          ))}
        </select>
      )}
    </div>
  );
}

export function seasonCardPath(leagueId: string, seasonId: string, cardId: number) {
  return `/leagues/${leagueId}/seasons/${seasonId}/cards/${cardId}`;
}

export default function RostersPage() {
  const { season } = useSeason();
  const rosters = useRosters();

  if (rosters.error) return <p className="bad">Couldn't load the rosters ({rosters.error})</p>;
  if (!rosters.data) return <Loading />;
  const r = rosters.data;

  return (
    <>
      <RosterNav rosters={r} />
      {r.players.length === 0 ? (
        <p className="muted">Nobody is playing this season yet. Players are added on the Players tab.</p>
      ) : (
        <>
          {season.status === "SETUP" && <p className="muted">Rosters fill up as the draft is made.</p>}
          {season.status === "DRAFTING" && <p className="muted">The draft is on: rosters update with every pick.</p>}
          <div className="roster-columns">
            {r.players.map((p) => <RosterColumn key={p.memberId} roster={p} />)}
          </div>
        </>
      )}
    </>
  );
}

// One player's cards as a compact list, grouped by deck. Hover a name for the card.
function RosterColumn({ roster }: { roster: Roster }) {
  return (
    <section className={`roster-column${roster.you ? " mine" : ""}`}>
      <h5 className="roster-head">
        <PlayerAvatar name={roster.displayName} passcode={roster.avatar} size="md" />
        <Link to={roster.memberId}>{roster.displayName}</Link>
        {roster.you && <span className="badge">you</span>}
      </h5>
      {roster.teamName && <p className="muted roster-team">{roster.teamName}</p>}
      <p className="muted roster-counts">{roster.counts.total} card{roster.counts.total === 1 ? "" : "s"}</p>
      {GROUPS.map(({ group, label }) => {
        const cards = roster.cards.filter((c) => c.group === group);
        if (cards.length === 0) return null;
        return (
          <div key={group}>
            <h6>{label} <span className="muted">{cards.length}</span></h6>
            <ul className="roster-list">
              {cards.map((c) => <RosterLine key={c.card.cardId} entry={c} />)}
            </ul>
          </div>
        );
      })}
    </section>
  );
}

function RosterLine({ entry }: { entry: RosterCard }) {
  const { league } = useLeague();
  const { season } = useSeason();
  const c = entry.card;
  const n = playable(c);
  return (
    <li className={n === 0 ? "unusable" : undefined}>
      <CardPreview cardId={c.cardId}>
        <Link to={seasonCardPath(league.id, season.id, c.cardId)}>{c.name}</Link>
      </CardPreview>
      {c.softBanned ? (
        <span className="badge ban-forbidden">soft-banned</span>
      ) : c.banStatus ? (
        <span className={`badge ban-${c.banStatus.toLowerCase()}`} title={`${statusLabel[c.banStatus]}: up to ${n}`}>{n}</span>
      ) : c.maxPlayable !== null && c.maxPlayable < 3 ? (
        <span className="badge" title={`League limit: up to ${n}`}>{n}</span>
      ) : null}
    </li>
  );
}
