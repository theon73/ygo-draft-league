import { useEffect, useRef, useState } from "react";
import PlayerAvatar from "../../components/PlayerAvatar";
import { Link, NavLink, useNavigate } from "react-router-dom";
import type { PoolCard, Roster, RosterCard, RosterGroup, Rosters } from "../../api/client";
import CardImage from "../../components/CardImage";
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
  const { season, draftVersion, messagesVersion } = useSeason();
  // Refetched as the draft moves and when a conversation changes (a trade may have gone through).
  return useApi<Rosters>(`/leagues/${league.id}/seasons/${season.id}/rosters`, `${draftVersion}-${messagesVersion}`);
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
          <RosterBoard players={r.players} />
        </>
      )}
    </>
  );
}

/**
 * Every roster side by side, in a row that scrolls sideways (YGO-37). With many players (or huge
 * rosters, as pack formats make) the row is wider than the screen, so:
 * - a strip of player chips above it jumps to a column, and lights the chips whose columns are in
 *   view: it's also the "where am I";
 * - ‹ › page through it, columns snap into place, and a fade marks the side with more.
 * The strip sticks to the top of the window while you scroll down a long roster, so getting to
 * another column never means scrolling back up (columns aren't height-capped: Theo preferred one
 * page scroll to a scroll inside each column).
 */
function RosterBoard({ players }: { players: Roster[] }) {
  const board = useRef<HTMLDivElement>(null);
  const [inView, setInView] = useState<Set<string>>(new Set());
  const [edges, setEdges] = useState({ left: false, right: false });
  const [flash, setFlash] = useState<string | null>(null);

  // Which columns are mostly visible inside the board (an IntersectionObserver rooted at the board).
  useEffect(() => {
    const root = board.current;
    if (!root) return;
    const observer = new IntersectionObserver((entries) => {
      setInView((prev) => {
        const next = new Set(prev);
        for (const e of entries) {
          const id = (e.target as HTMLElement).dataset.member!;
          if (e.intersectionRatio >= 0.6) next.add(id); else next.delete(id);
        }
        return next;
      });
    }, { root, threshold: [0, 0.6, 1] });
    root.querySelectorAll<HTMLElement>("[data-member]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [players]);

  // Whether there's more to either side (for the fades and the ‹ › buttons).
  useEffect(() => {
    const root = board.current;
    if (!root) return;
    const update = () => setEdges({
      left: root.scrollLeft > 4,
      right: root.scrollLeft + root.clientWidth < root.scrollWidth - 4,
    });
    update();
    root.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    return () => { root.removeEventListener("scroll", update); window.removeEventListener("resize", update); };
  }, [players]);

  const jumpTo = (memberId: string) => {
    const column = board.current?.querySelector<HTMLElement>(`[data-member="${memberId}"]`);
    if (!column || !board.current) return;
    board.current.scrollTo({ left: column.offsetLeft - board.current.offsetLeft, behavior: "smooth" });
    setFlash(memberId);
    window.setTimeout(() => setFlash((f) => (f === memberId ? null : f)), 1200);
  };
  const page = (dir: 1 | -1) => board.current?.scrollBy({ left: dir * board.current.clientWidth * 0.9, behavior: "smooth" });
  const scrolls = edges.left || edges.right;

  return (
    <div className="roster-board">
      {scrolls && (
        <div className="roster-strip">
          <button type="button" className="ghost strip-arrow" aria-label="Previous rosters" disabled={!edges.left} onClick={() => page(-1)}>‹</button>
          <ul>
            {players.map((p) => (
              <li key={p.memberId}>
                <button type="button" className={`roster-chip${inView.has(p.memberId) ? " in-view" : ""}${p.you ? " you" : ""}`}
                        onClick={() => jumpTo(p.memberId)} title={p.teamName ? `${p.displayName} · ${p.teamName}` : p.displayName}>
                  <PlayerAvatar name={p.displayName} passcode={p.avatar} size="xs" />
                  {p.displayName}
                </button>
              </li>
            ))}
          </ul>
          <button type="button" className="ghost strip-arrow" aria-label="More rosters" disabled={!edges.right} onClick={() => page(1)}>›</button>
        </div>
      )}
      <div className={`roster-columns-wrap${edges.left ? " more-left" : ""}${edges.right ? " more-right" : ""}`}>
        <div className="roster-columns" ref={board}>
          {players.map((p) => <RosterColumn key={p.memberId} roster={p} flash={flash === p.memberId} />)}
        </div>
      </div>
    </div>
  );
}

// One player's cards as a compact list, grouped by deck. Hover a name for the card.
function RosterColumn({ roster, flash }: { roster: Roster; flash: boolean }) {
  return (
    <section className={`roster-column${roster.you ? " mine" : ""}${flash ? " flash" : ""}`} data-member={roster.memberId}>
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
        <span className="roster-card">
          <CardImage passcode={c.passcode} size="small" alt="" className="chip-art" />
          <Link to={seasonCardPath(league.id, season.id, c.cardId)}>{c.name}</Link>
        </span>
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
