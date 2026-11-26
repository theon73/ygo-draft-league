import { Link, Navigate } from "react-router-dom";
import { useLeague } from "./LeagueLayout";

// /leagues/:id opens the current season. With no seasons yet, it says what to do next.
export default function LeagueHome() {
  const { league } = useLeague();
  if (league.currentSeasonId) return <Navigate to={`seasons/${league.currentSeasonId}`} replace />;
  return (
    <>
      <h3>No seasons yet</h3>
      {league.myRole === "OWNER" ? (
        <p>
          Next steps: <Link to="members">invite your players</Link>, then <Link to="seasons">create the first season</Link>.
        </p>
      ) : (
        <p className="muted">The league owner hasn't set up a season yet.</p>
      )}
    </>
  );
}
