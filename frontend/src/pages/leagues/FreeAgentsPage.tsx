import InfoTip from "../../components/InfoTip";
import { useState } from "react";
import { apiPost, type FreeAgency, type PoolCard, type PoolCardPage, type PoolFacets, type PoolSummary, type Roster } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import PoolFilters from "./PoolFilters";
import PoolGrid from "./PoolGrid";
import { activeFilterCount, poolQueryString } from "./poolQuery";
import { GROUPS, RosterNav, useRosters } from "./RostersPage";
import { useSeason } from "./SeasonLayout";
import { usePoolParams } from "./usePoolParams";

const PAGE_SIZE = 50;

// The free agents: the pool's "available only" list (in the pool, not soft-banned, not Forbidden
// unless the draft allows it, and held by nobody), with the pool's filters. Once the draft is
// over, players claim from here (YGO-23): each claim picks up one card and drops one, so rosters
// keep their size, and counts toward the weekly limit.
export default function FreeAgentsPage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const seasonBase = `/leagues/${league.id}/seasons/${season.id}`;
  const base = `${seasonBase}/pool`;
  const rosters = useRosters();
  const fa = useApi<FreeAgency>(`${seasonBase}/free-agency`);
  const summary = useApi<PoolSummary>(base);
  const facets = useApi<PoolFacets>(`${base}/facets`);
  const { params, setParam, setParams, setList, clearFilters } = usePoolParams();
  const page = Number(params.get("page") ?? 0);
  const query = new URLSearchParams(poolQueryString(params, page, PAGE_SIZE));
  query.set("available", "true");
  const cards = useApi<PoolCardPage>(`${base}/cards?${query.toString()}`, String(draftVersion));

  // Whom claims are for: yourself, or (commissioners) any player. Defaults to you if you play.
  const [chosen, setChosen] = useState<string | null>(null);
  const [claiming, setClaiming] = useState<PoolCard | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const players = fa.data?.players ?? [];
  const claimant = players.find((p) => p.memberId === chosen) ?? players.find((p) => p.you) ?? players[0] ?? null;
  const canClaim = !!fa.data?.open && !!claimant && (claimant.left === null || claimant.left > 0);
  const claimantRoster = rosters.data?.players.find((p) => p.memberId === claimant?.memberId) ?? null;

  const lastPage = cards.data ? Math.max(0, Math.ceil(cards.data.total / cards.data.pageSize) - 1) : 0;
  const hasFilters = activeFilterCount(params) > 0 || params.getAll("kind").length > 0 || !!params.get("q");

  const claimed = (text: string) => {
    setClaiming(null);
    setDone(text);
    cards.reload();
    rosters.reload();
    fa.reload();
  };

  return (
    <>
      <RosterNav rosters={rosters.data} />

      {fa.data && (fa.data.open ? (
        players.length > 0 && claimant && (
          <p className="claim-bar">
            <InfoTip>
              Pool cards nobody holds{season.status === "ACTIVE" ? "" : " (until the draft is over, these are the cards still to draft)"}.
              Soft-banned cards and, unless the draft allows them, Forbidden cards aren't listed. A claim drops one of
              {claimant.you ? " your" : " their"} cards in exchange, so rosters keep their size.
            </InfoTip>
            {players.length > 1 && (
              <label className="inline-label">Claiming for{" "}
                <select value={claimant.memberId} onChange={(e) => { setChosen(e.target.value); setClaiming(null); }}>
                  {players.map((p) => <option key={p.memberId} value={p.memberId}>{p.displayName}{p.you ? " (you)" : ""}</option>)}
                </select>{" "}
              </label>
            )}
            <span className={`pill${canClaim ? " accent" : ""}`}>
              {claimant.limit === null
                ? "Unlimited claims"
                : `${claimant.left} of ${claimant.limit} claim${claimant.limit === 1 ? "" : "s"} left`}
              {` · week ${fa.data.budgetWeek}`}
            </span>
            {fa.data.week === 0 && <span className="muted small">before week 1 counts as week 1</span>}
          </p>
        )
      ) : (
        <p className="muted">{fa.data.closedReason}.</p>
      ))}
      {done && <p className="good">{done}</p>}
      {claiming && claimant && (
        <ClaimPanel key={`${claiming.cardId}-${claimant.memberId}`} seasonBase={seasonBase} card={claiming} memberId={claimant.memberId}
                    roster={claimantRoster} onCancel={() => setClaiming(null)} onClaimed={claimed} />
      )}

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
              <PoolGrid cards={cards.data.items} hideOwner
                        action={canClaim ? (c) => <button onClick={() => { setDone(null); setClaiming(c); }}>Claim</button> : undefined} />
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

// Confirms a claim: choose the card to drop from the claimant's roster, grouped by deck.
function ClaimPanel({ seasonBase, card, memberId, roster, onCancel, onClaimed }: {
  seasonBase: string;
  card: PoolCard;
  memberId: string;
  roster: Roster | null;
  onCancel: () => void;
  onClaimed: (message: string) => void;
}) {
  const [drop, setDrop] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cards = roster?.cards ?? [];

  const confirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await apiPost(`${seasonBase}/free-agency/claims`, { memberId, claimCardId: card.cardId, dropCardId: Number(drop) });
      const dropped = cards.find((c) => c.card.cardId === Number(drop))?.card.name ?? "a card";
      onClaimed(`${roster?.you ? "You" : roster?.displayName} claimed ${card.name} and dropped ${dropped}.`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <p>
        <strong>Claim {card.name}</strong> for {roster?.you ? "yourself" : roster?.displayName ?? "this player"}. Which card goes back to free agency?
      </p>
      {cards.length === 0 ? (
        <p className="muted">There's nothing on this roster to drop.</p>
      ) : (
        <p>
          <select value={drop} onChange={(e) => setDrop(e.target.value)} aria-label="Card to drop">
            <option value="">Choose a card to drop…</option>
            {GROUPS.map(({ group, label }) => {
              const inGroup = cards.filter((c) => c.group === group);
              return inGroup.length === 0 ? null : (
                <optgroup key={group} label={label}>
                  {inGroup.map((c) => <option key={c.card.cardId} value={c.card.cardId}>{c.card.name}</option>)}
                </optgroup>
              );
            })}
          </select>{" "}
          <button className="primary" onClick={() => void confirm()} disabled={!drop || busy}>Claim and drop</button>{" "}
          <button className="ghost" onClick={onCancel} disabled={busy}>Cancel</button>
        </p>
      )}
      {error && <p className="bad">{error}</p>}
    </div>
  );
}
