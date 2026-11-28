import { createContext, useContext, useEffect, useRef, useState } from "react";
import { Link, Navigate, NavLink, Outlet, useLocation, useMatch, useNavigate, useParams } from "react-router-dom";
import type { DraftChanged, SeasonDetail, SeasonStatus } from "../../api/client";
import { Loading } from "../../components/Feedback";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonStatusLabel } from "./seasonStatus";
import CountBadge from "../../components/CountBadge";
import { openEventStream } from "../../hooks/eventStream";

// Everything under /leagues/:leagueId/seasons/:seasonId. Same pattern as LeagueLayout: load the
// season once, show its tabs, and give it (and reload) to every page below via useSeason().
//
// It also holds the live draft connection (YGO-17), so every season tab knows when the draft moves
// and can say "you're on the clock". The server sends a small "the draft changed" signal; pages
// refetch what they show when draftVersion goes up. Messages work the same way (YGO-35): a "one of
// your conversations changed" signal bumps messagesVersion and refreshes the unread counts.

interface SeasonContext {
  season: SeasonDetail;
  reload: () => void;
  reloadLeague: () => void;
  draftVersion: number; // goes up on every live draft event (including on (re)connecting)
  draftLive: DraftChanged | null; // the latest event, null until connected
  messagesVersion: number; // goes up when one of your conversations changes (YGO-35)
}

const SeasonCtx = createContext<SeasonContext | null>(null);

export function useSeason(): SeasonContext {
  const ctx = useContext(SeasonCtx);
  if (!ctx) throw new Error("useSeason must be used inside a season page");
  return ctx;
}

const TAB_LABEL = {
  overview: "Overview", players: "Players", rules: "Rules", pool: "Pool", draft: "Draft", rosters: "Rosters", messages: "Messages",
  schedule: "Schedule", standings: "Standings", transactions: "Transactions",
} as const;
type Tab = keyof typeof TAB_LABEL;

// The tabs a season shows, most useful first, by its state: setup is about players, rules, pool and
// the draft; a running season about matches. The other pages still open by URL (and then show
// their tab), they're just not offered where they'd be empty.
// Every state opens on the Overview (YGO-31), which summarizes it and says what needs you.
const TABS: Record<SeasonStatus, Tab[]> = {
  // Messages work in every state (YGO-35); trade offers wait until the season is active.
  SETUP: ["overview", "players", "rules", "pool", "draft", "messages"],
  DRAFTING: ["overview", "draft", "rosters", "messages", "pool", "players", "rules"],
  // Most used first; draft, players and rules are mostly history or for commissioners. The playoffs
  // are a section of the Schedule tab.
  ACTIVE: ["overview", "schedule", "rosters", "pool", "standings", "messages", "transactions", "draft", "players", "rules"],
  COMPLETE: ["overview", "schedule", "rosters", "pool", "standings", "messages", "transactions", "draft", "players", "rules"],
};

/** "Week 4 of 9" for a running season: before week 1, the weeks, then after the regular season. */
function weekLabel(s: SeasonDetail): string | null {
  if (s.status !== "ACTIVE") return null;
  if (s.playoffsStarted) return "Playoffs";
  if (s.currentWeek == null || !s.weekCount) return null;
  if (s.currentWeek === 0) return "Before week 1";
  if (s.currentWeek > s.weekCount) return "Regular season over";
  return `Week ${s.currentWeek} of ${s.weekCount}`;
}

/** Your part in the season: "Commissioner", "Player", both, or following along. */
function roleLabel(s: SeasonDetail): string {
  if (s.canManage && s.youPlay) return "Commissioner · player";
  if (s.canManage) return "Commissioner";
  return s.youPlay ? "Player" : "Following";
}

/** /leagues/:id/seasons/:id opens the season on its first tab for its state. */
export function SeasonIndex() {
  const { season } = useSeason();
  return <Navigate to={TABS[season.status][0]} replace />;
}

export default function SeasonLayout() {
  const { league, reload: reloadLeague } = useLeague();
  const { seasonId = "" } = useParams();
  const navigate = useNavigate();
  const season = useApi<SeasonDetail>(`/leagues/${league.id}/seasons/${encodeURIComponent(seasonId)}`);
  const onDraftTab = useMatch("/leagues/:leagueId/seasons/:seasonId/draft") !== null;
  const location = useLocation();
  const { draftVersion, draftLive } = useLiveDraft(league.id, season.data, onDraftTab, season.reload, reloadLeague);
  const myTurn = !!draftLive && draftLive.status === "IN_PROGRESS" && !draftLive.paused && draftLive.onTheClockSeat !== null
    && season.data?.participants.find((p) => p.you)?.seat === draftLive.onTheClockSeat;
  useTitlePrefix(myTurn ? "Your pick · " : "");
  const messagesVersion = useLiveMessages(league.id, season.data, season.reload, reloadLeague);

  if (season.status === 404) {
    return <p className="bad">Season not found. <Link to="../seasons">All seasons</Link></p>;
  }
  if (season.error) return <p className="bad">Couldn't load the season ({season.error})</p>;
  if (!season.data) return <Loading what="the season" />;

  const s = season.data;
  // The tab being viewed, e.g. "rosters" for …/rosters/free-agents; added to the row if the state hides it.
  const here = location.pathname.split(`/seasons/${s.id}/`)[1]?.split("/")[0] as Tab | undefined;
  // (…/playoffs only redirects to the Schedule tab, so it needs no tab of its own.)
  const tabs = TABS[s.status];
  const shown = here && here in TAB_LABEL && !tabs.includes(here) ? [...tabs, here] : tabs;
  return (
    <>
      <div className="season-head">
        <h2>{s.name}</h2>
        <span className={`pill status-${s.status}`}>{seasonStatusLabel[s.status]}</span>
        {weekLabel(s) && <span className="pill">{weekLabel(s)}</span>}
        <span className="pill role">{roleLabel(s)}</span>
        {/* Your messages, with what's unread (YGO-34): always in view, even when the tab row scrolls on a phone. */}
        {s.youPlay && (
          <Link to="messages" className={`chat-link${s.unreadMessages ? " unread" : ""}`} title="Messages"
                aria-label={s.unreadMessages ? `Messages, ${s.unreadMessages} unread` : "Messages"}>
            <ChatIcon />
            <CountBadge count={s.unreadMessages} />
          </Link>
        )}
        {league.seasons.length > 1 && (
          <label className="season-switch">
            Season
            <select
              value={s.id}
              onChange={(e) => navigate(`/leagues/${league.id}/seasons/${e.target.value}`)}
            >
              {league.seasons.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}{x.id === league.currentSeasonId ? " (current)" : ""}{x.unread ? ` · ${x.unread} unread` : ""}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      {/* The setup wizard keeps its own steps in view instead of the tabs (YGO-32). */}
      {here !== ("setup" as string) && <nav className="tabs" aria-label="Season">
        {shown.map((t) => (
          <NavLink key={t} to={t}>{TAB_LABEL[t]}{t === "messages" && <CountBadge count={s.unreadMessages} />}</NavLink>
        ))}
      </nav>}
      {myTurn && !onDraftTab && (
        <div className="callout">
          <span className="grow"><strong>You're on the clock.</strong> It's your pick in the draft.</span>
          <Link className="button primary sm" to="draft">Go to the draft</Link>
        </div>
      )}
      <SeasonCtx.Provider value={{ season: s, reload: season.reload, reloadLeague, draftVersion, draftLive, messagesVersion }}>
        <Outlet />
      </SeasonCtx.Provider>
    </>
  );
}

function ChatIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12a8 8 0 0 1-11.8 7L4 20l1.1-4.4A8 8 0 1 1 21 12Z" />
    </svg>
  );
}

// Listens to the season's draft events while there's something to follow: during the draft from
// any tab, and on the Draft tab during setup (to see it start). EventSource is the browser's
// built-in SSE client: it sends the session cookie; openEventStream keeps it reconnected, and the
// server answers every (re)connect with the current state, so nothing is missed.
function useLiveDraft(leagueId: string, season: SeasonDetail | null, onDraftTab: boolean,
                      reloadSeason: () => void, reloadLeague: () => void) {
  const [draftVersion, setDraftVersion] = useState(0);
  const [draftLive, setDraftLive] = useState<DraftChanged | null>(null);
  const seasonId = season?.id;
  const listen = !!season && (season.status === "DRAFTING" || (season.status === "SETUP" && onDraftTab));
  // Kept in refs so the connection isn't reopened when these functions change identity.
  const reloads = useRef({ reloadSeason, reloadLeague });
  reloads.current = { reloadSeason, reloadLeague };

  useEffect(() => {
    if (!listen || !seasonId) return;
    let lastStatus: DraftChanged["status"] | null = null;
    setDraftLive(null); // nothing known about this season's draft until its first event
    return openEventStream(`/api/leagues/${leagueId}/seasons/${seasonId}/draft/events`, { draft: (e) => {
      const change = JSON.parse(e.data) as DraftChanged;
      // Started or completed: the season's status changed too, so refresh the header and league.
      if (lastStatus !== null && change.status !== lastStatus) {
        reloads.current.reloadSeason();
        reloads.current.reloadLeague();
      }
      lastStatus = change.status;
      setDraftLive(change);
      setDraftVersion((v) => v + 1);
    } });
  }, [listen, leagueId, seasonId]);

  return { draftVersion, draftLive: listen ? draftLive : null };
}

// Listens for changes to your conversations (YGO-35) while you play in the season or commission it
// (commissioners act for placeholders and approve trades). Each event is a bare signal: refresh the
// unread counts (season header, league switcher) and bump the version the Messages page refetches
// on. The server sends one on every (re)connect; the first is skipped since the page just loaded,
// but one after a reconnect catches up on anything missed while disconnected.
function useLiveMessages(leagueId: string, season: SeasonDetail | null, reloadSeason: () => void, reloadLeague: () => void) {
  const [version, setVersion] = useState(0);
  const seasonId = season?.id;
  const listen = !!season && (season.youPlay || season.canManage);
  const reloads = useRef({ reloadSeason, reloadLeague });
  reloads.current = { reloadSeason, reloadLeague };

  useEffect(() => {
    if (!listen || !seasonId) return;
    let first = true;
    return openEventStream(`/api/leagues/${leagueId}/seasons/${seasonId}/trades/events`, { messages: () => {
      if (first) { first = false; return; }
      reloads.current.reloadSeason();
      reloads.current.reloadLeague();
      setVersion((v) => v + 1);
    } });
  }, [listen, leagueId, seasonId]);

  return version;
}

// Puts a prefix in front of the page title (e.g. "Your pick · "), so a background browser tab shows it.
function useTitlePrefix(prefix: string) {
  useEffect(() => {
    if (!prefix) return;
    const original = document.title;
    document.title = prefix + original;
    return () => { document.title = original; };
  }, [prefix]);
}
