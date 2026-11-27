import { Link, useSearchParams } from "react-router-dom";
import { useSession } from "../auth/session";

// The landing page. YGO-30 turns it into a dashboard of what needs you across your leagues.
export default function Home() {
  const { session } = useSession();
  const [params] = useSearchParams();
  const user = session?.user;

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
