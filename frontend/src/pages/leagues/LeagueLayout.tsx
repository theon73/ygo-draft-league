import { Link, NavLink, Outlet, useOutletContext, useParams } from "react-router-dom";
import type { LeagueDetail } from "../../api/client";
import { useSession } from "../../auth/session";
import { useApi } from "../../hooks/useApi";
import { roleLabel } from "./roles";

// The shell for everything under /leagues/:leagueId. It loads the league once and renders the
// league's sub-navigation; the child route (overview, standings, ...) renders in <Outlet/> and
// gets the loaded league, plus a reload() to call after changing it, through React Router's
// outlet context, so children don't refetch it themselves.

interface LeagueContext {
  league: LeagueDetail;
  reload: () => void;
}

export function useLeague(): LeagueContext {
  return useOutletContext<LeagueContext>();
}

const TABS = [
  { to: "", label: "Overview", end: true },
  { to: "standings", label: "Standings" },
  { to: "rosters", label: "Rosters" },
  { to: "draft", label: "Draft" },
  { to: "transactions", label: "Transactions" },
];

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
  return (
    <section>
      <p className="muted"><Link to="/leagues">My leagues</Link> / {l.name}</p>
      <h2>
        {l.name} <span className="badge">{roleLabel[l.myRole]}</span>
      </h2>
      <nav className="tabs">
        {TABS.map((t) => (
          <NavLink key={t.label} to={t.to} end={t.end}>{t.label}</NavLink>
        ))}
      </nav>
      <Outlet context={{ league: l, reload: league.reload } satisfies LeagueContext} />
    </section>
  );
}
