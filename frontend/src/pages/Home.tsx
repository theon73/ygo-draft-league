import { Link, useSearchParams } from "react-router-dom";
import type { LeagueSummary, NeedsYouItem } from "../api/client";
import { useSession } from "../auth/session";
import { EmptyState, Loading } from "../components/Feedback";
import NeedsYou from "../components/NeedsYou";
import { useApi } from "../hooks/useApi";
import LeagueCards from "./leagues/LeagueCards";

// The landing page: signed out, what the site is; signed in, a dashboard (YGO-31) of what needs
// you across your leagues, then the leagues themselves.
export default function Home() {
  const { session } = useSession();
  const [params] = useSearchParams();
  const user = session?.user;
  if (user) return <Dashboard name={user.displayName} identity={user.identity} />;

  return (
    <section>
      {params.get("login") === "failed" && <p className="bad">Sign-in didn't work. Please try again.</p>}
      <div className="hero">
        <h1>Draft a format. <em>Play the season.</em></h1>
        <p>
          Run your own Yu-Gi-Oh draft league: pick a format and card pool, draft it with your friends, then trade, claim free
          agents and play it out to a champion.
        </p>
        <div className="hero-actions">
          {user ? (
            <Link className="button primary" to="/leagues">Go to my leagues</Link>
          ) : session?.providers.includes("google") ? (
            <a className="button primary" href="/api/oauth2/authorization/google">Sign in with Google</a>
          ) : null}
          <Link className="button" to="/explore">Browse the card catalog</Link>
        </div>
      </div>
      <div className="features">
        <div className="panel"><h4>Any format</h4><p>Choose a TCG date and ban list; the card pool and copy limits follow from it.</p></div>
        <div className="panel"><h4>A live draft</h4><p>Snake or linear, with pick timers, queues and Discord pings when you're on the clock.</p></div>
        <div className="panel"><h4>A full season</h4><p>Trades, free agency, a round-robin schedule, standings and playoffs to crown a champion.</p></div>
      </div>
    </section>
  );
}

function Dashboard({ name, identity }: { name: string; identity: string }) {
  // Keyed on the identity so the dev switcher's "log in as…" refetches.
  const needs = useApi<NeedsYouItem[]>("/me/needs-you", identity);
  const leagues = useApi<LeagueSummary[]>("/leagues", identity);
  const first = name.split(/\s+/)[0];
  return (
    <section className="dashboard">
      <h1>Welcome back, {first}</h1>
      <h4>Needs you</h4>
      {needs.error ? <p className="bad">Couldn't load what needs you ({needs.error})</p>
        : !needs.data ? <Loading />
        : needs.data.length === 0 ? <p className="muted all-clear">Nothing needs you right now.</p>
        : <NeedsYou items={needs.data} showWhere />}

      <div className="section-head">
        <h4>Your leagues</h4>
        <Link className="button sm" to="/leagues">Create or manage leagues</Link>
      </div>
      {leagues.data && (leagues.data.length === 0 ? (
        <EmptyState title="No leagues yet" actions={<Link className="button primary" to="/leagues">Create a league</Link>}>
          <p>Create one, or ask a league owner for an invite link.</p>
        </EmptyState>
      ) : <LeagueCards leagues={leagues.data} />)}
    </section>
  );
}
