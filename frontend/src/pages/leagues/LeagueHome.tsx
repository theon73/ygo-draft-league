import { Link, Navigate } from "react-router-dom";
import { EmptyState } from "../../components/Feedback";
import { useLeague } from "./LeagueLayout";

// /leagues/:id opens the current season. With no seasons yet, it says what to do next.
export default function LeagueHome() {
  const { league } = useLeague();
  if (league.currentSeasonId) return <Navigate to={`seasons/${league.currentSeasonId}`} replace />;
  return league.myRole === "OWNER" ? (
    <EmptyState
      title="No seasons yet"
      actions={<>
        <Link className="button" to="members">Invite players</Link>
        <Link className="button primary" to="seasons">Create the first season</Link>
      </>}
    >
      <p>Invite your players, then create the first season to pick its format, card pool and draft.</p>
    </EmptyState>
  ) : (
    <EmptyState title="No seasons yet"><p>The league owner hasn't set up a season yet.</p></EmptyState>
  );
}
