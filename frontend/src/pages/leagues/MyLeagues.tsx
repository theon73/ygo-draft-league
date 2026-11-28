import { Link } from "react-router-dom";
import type { LeagueSummary } from "../../api/client";
import { useSession } from "../../auth/session";
import { useApi } from "../../hooks/useApi";
import LeagueCards from "./LeagueCards";
import { EmptyState, Loading } from "../../components/Feedback";

// Your leagues. Creating one is a short guided flow (YGO-32, NewLeagueWizard) that ends in its
// first season's setup.
export default function MyLeagues() {
  const { session } = useSession();
  const user = session?.user;
  // Refetch when the signed-in account changes (e.g. via the dev switcher).
  const leagues = useApi<LeagueSummary[]>(user ? "/leagues" : null, user?.identity);

  if (!session) return <Loading />;
  if (!user) {
    return <EmptyState title="My leagues"><p>Sign in to see your leagues or create one.</p></EmptyState>;
  }

  return (
    <section>
      <div className="section-head page-head">
        <h1>My leagues</h1>
        <Link className="button primary" to="/leagues/new">Create a league</Link>
      </div>
      {leagues.error && <p className="bad">Couldn't load your leagues ({leagues.error})</p>}
      {leagues.loading && <Loading />}
      {leagues.data &&
        (leagues.data.length === 0 ? (
          <EmptyState title="No leagues yet" actions={<Link className="button primary" to="/leagues/new">Create a league</Link>}>
            <p>Create one and you'll be its owner: you invite people, create seasons and commission them. Or ask a league owner for an invite link.</p>
          </EmptyState>
        ) : (
          <LeagueCards leagues={leagues.data} />
        ))}
    </section>
  );
}
