import { createContext, useContext } from "react";
import { Link, NavLink, Outlet, useNavigate, useParams } from "react-router-dom";
import type { SeasonDetail } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonStatusLabel } from "./seasonStatus";

// Everything under /leagues/:leagueId/seasons/:seasonId. Same pattern as LeagueLayout: load the
// season once, show its tabs, and give it (and reload) to every page below via useSeason().

interface SeasonContext {
  season: SeasonDetail;
  reload: () => void;
  reloadLeague: () => void;
}

const SeasonCtx = createContext<SeasonContext | null>(null);

export function useSeason(): SeasonContext {
  const ctx = useContext(SeasonCtx);
  if (!ctx) throw new Error("useSeason must be used inside a season page");
  return ctx;
}

const TABS = [
  { to: "", label: "Setup", end: true },
  { to: "draft", label: "Draft" },
  { to: "rosters", label: "Rosters" },
  { to: "standings", label: "Standings" },
  { to: "transactions", label: "Transactions" },
];

export default function SeasonLayout() {
  const { league, reload: reloadLeague } = useLeague();
  const { seasonId = "" } = useParams();
  const navigate = useNavigate();
  const season = useApi<SeasonDetail>(`/leagues/${league.id}/seasons/${encodeURIComponent(seasonId)}`);

  if (season.status === 404) {
    return <p>Season not found. <Link to="../seasons">All seasons</Link></p>;
  }
  if (season.error) return <p className="bad">Couldn't load the season ({season.error})</p>;
  if (!season.data) return <p className="muted">Loading…</p>;

  const s = season.data;
  return (
    <>
      <div className="season-head">
        <h3>
          {s.name} <span className="muted">· {seasonStatusLabel[s.status]}</span>
        </h3>
        {league.seasons.length > 1 && (
          <select
            aria-label="Switch season"
            value={s.id}
            onChange={(e) => navigate(`/leagues/${league.id}/seasons/${e.target.value}`)}
          >
            {league.seasons.map((x) => (
              <option key={x.id} value={x.id}>{x.name}</option>
            ))}
          </select>
        )}
      </div>
      <nav className="tabs">
        {TABS.map((t) => (
          <NavLink key={t.label} to={t.to} end={t.end}>{t.label}</NavLink>
        ))}
      </nav>
      <SeasonCtx.Provider value={{ season: s, reload: season.reload, reloadLeague }}>
        <Outlet />
      </SeasonCtx.Provider>
    </>
  );
}
