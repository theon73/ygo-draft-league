import { createContext, useContext } from "react";
import { Link, NavLink, Outlet, useParams } from "react-router-dom";
import type { LeagueDetail } from "../../api/client";
import { useSession } from "../../auth/session";
import { useApi } from "../../hooks/useApi";
import { roleLabel } from "./roles";

// The shell for everything under /leagues/:leagueId. It loads the league once and renders the
// league-level tabs; the pages below it get the loaded league, plus a reload() to call after
// changing it, from useLeague(), so they don't refetch it themselves.
//
// This uses a React context rather than React Router's outlet context on purpose: outlet context
// only reaches the *direct* child route, and season pages sit two levels down (league → season →
// setup), where they'd get the season layout's context instead.
//
// A league is the lasting group (members, settings); a season is one run of play inside it. The
// league opens on its current season, so most of the time the season layer stays out of the way.

interface LeagueContext {
  league: LeagueDetail;
  reload: () => void;
}

const LeagueCtx = createContext<LeagueContext | null>(null);

export function useLeague(): LeagueContext {
  const ctx = useContext(LeagueCtx);
  if (!ctx) throw new Error("useLeague must be used inside a league page");
  return ctx;
}

export default function LeagueLayout() {
  const { leagueId } = useParams();
  const { session } = useSession();
  const user = session?.user;
  const league = useApi<LeagueDetail>(user && leagueId ? `/leagues/${encodeURIComponent(leagueId)}` : null, user?.identity);

  if (!session) return <p className="muted">Loading…</p>;
  if (!user) return <p>Sign in to see this league.</p>;
  if (league.status === 404) {
    return (
      <section>
        <h2>League not found</h2>
        <p>It doesn't exist, or you're not a member. <Link to="/leagues">Back to my leagues</Link></p>
      </section>
    );
  }
  if (league.error) return <p className="bad">Couldn't load the league ({league.error})</p>;
  if (!league.data) return <p className="muted">Loading…</p>;

  const l = league.data;
  const current = l.seasons.find((s) => s.id === l.currentSeasonId);
  return (
    <section>
      <p className="muted"><Link to="/leagues">My leagues</Link> / {l.name}</p>
      <h2>
        {l.name} <span className="badge">{roleLabel[l.myRole]}</span>
      </h2>
      <nav className="tabs">
        {current && <NavLink to={`seasons/${current.id}`}>{current.name}</NavLink>}
        <NavLink to="seasons" end>Seasons</NavLink>
        <NavLink to="members">Members</NavLink>
        <NavLink to="settings">Settings</NavLink>
      </nav>
      <LeagueCtx.Provider value={{ league: l, reload: league.reload }}>
        <Outlet />
      </LeagueCtx.Provider>
    </section>
  );
}
