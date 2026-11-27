import { Link } from "react-router-dom";
import type { LeagueSummary } from "../../api/client";
import { roleLabel } from "./roles";
import { seasonStatusLabel } from "./seasonStatus";

/** Your leagues as cards, each with its current season and where it stands (My leagues and Home). */
export default function LeagueCards({ leagues }: { leagues: LeagueSummary[] }) {
  return (
    <ul className="league-cards">
      {leagues.map((l) => (
        <li key={l.id}>
          <Link to={`/leagues/${l.id}`}>
            <strong>{l.name}</strong>
            <span className="badge neutral">{roleLabel[l.myRole]}</span>
            <span className="muted">{l.memberCount} member{l.memberCount === 1 ? "" : "s"}</span>
            {l.currentSeason ? (
              <span className="league-season">
                {l.currentSeason.name} <span className={`pill status-${l.currentSeason.status}`}>{seasonStatusLabel[l.currentSeason.status]}</span>
              </span>
            ) : <span className="league-season muted">No seasons yet</span>}
          </Link>
        </li>
      ))}
    </ul>
  );
}
