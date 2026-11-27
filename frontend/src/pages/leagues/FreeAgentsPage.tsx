import type { PoolCardPage, PoolFacets, PoolSummary } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import PoolFilters from "./PoolFilters";
import PoolGrid from "./PoolGrid";
import { activeFilterCount, poolQueryString } from "./poolQuery";
import { RosterNav, useRosters } from "./RostersPage";
import { useSeason } from "./SeasonLayout";
import { usePoolParams } from "./usePoolParams";

const PAGE_SIZE = 50;

// The free agents: the pool's "available only" list (in the pool, not soft-banned, not Forbidden
// unless the draft allows it, and held by nobody), with the pool's filters. Claiming comes with
// free agency (YGO-23); for now this is for looking.
export default function FreeAgentsPage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/pool`;
  const rosters = useRosters();
  const summary = useApi<PoolSummary>(base);
  const facets = useApi<PoolFacets>(`${base}/facets`);
  const { params, setParam, setParams, setList, clearFilters } = usePoolParams();
  const page = Number(params.get("page") ?? 0);
  const query = new URLSearchParams(poolQueryString(params, page, PAGE_SIZE));
  query.set("available", "true");
  const cards = useApi<PoolCardPage>(`${base}/cards?${query.toString()}`, String(draftVersion));

  const lastPage = cards.data ? Math.max(0, Math.ceil(cards.data.total / cards.data.pageSize) - 1) : 0;
  const hasFilters = activeFilterCount(params) > 0 || params.getAll("kind").length > 0 || !!params.get("q");

  return (
    <>
      <h4>Free agents</h4>
      <RosterNav rosters={rosters.data} />
      <p className="muted">
        Pool cards nobody holds that can be taken{season.status === "ACTIVE" ? "" : " (until the draft is over, these are the cards still to draft)"}.
        Soft-banned cards and, unless the draft allows them, Forbidden cards aren't listed.
      </p>
      {summary.data && summary.data.generatedAt === null ? (
        <p className="muted">There's no pool yet.</p>
      ) : (
        <>
          <PoolFilters key={`${params.get("q") ?? ""}|${params.get("cardText") ?? ""}`} params={params} setOne={setParam} setSome={setParams}
                       setList={setList} clear={clearFilters} facets={facets.data} hasBanList={!!summary.data?.banList} />
          {cards.error && <p className="bad">Couldn't load cards ({cards.error})</p>}
          {cards.data && (
            <>
              <div className="results-bar">
                <span className="muted">
                  {cards.data.total.toLocaleString()} card{cards.data.total === 1 ? "" : "s"}
                  {hasFilters && " match"}
                  {cards.data.total > cards.data.pageSize && ` · page ${page + 1} of ${lastPage + 1}`}
                </span>
              </div>
              <PoolGrid cards={cards.data.items} hideOwner />
              {cards.data.total > cards.data.pageSize && (
                <p>
                  <button disabled={page === 0} onClick={() => setParam("page", String(page - 1))}>Previous</button>{" "}
                  <button disabled={page >= lastPage} onClick={() => setParam("page", String(page + 1))}>Next</button>
                </p>
              )}
            </>
          )}
        </>
      )}
    </>
  );
}
