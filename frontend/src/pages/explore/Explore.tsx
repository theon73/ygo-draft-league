import { useState } from "react";
import { Link } from "react-router-dom";
import {
  apiPost, type BanListInfo, type CatalogCard, type CatalogPage, type ExploreSummary, type FormatSummary, type PoolFacets, type SyncResult,
} from "../../api/client";
import { useSession } from "../../auth/session";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { cardStats } from "../../components/cardStats";
import { Loading } from "../../components/Feedback";
import InfoTip from "../../components/InfoTip";
import { useApi } from "../../hooks/useApi";
import PoolFilters from "../leagues/PoolFilters";
import { poolQueryString } from "../leagues/poolQuery";
import { usePoolParams } from "../leagues/usePoolParams";
import { statusLabel } from "./banStatus";

const PAGE_SIZE = 50;

/** "2008-03-01" → "Mar 1, 2008" (a calendar date: no time zone shift). */
export function fmtDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

/** The Cards page's URL for a format (a list's own date as the cutoff). */
export const formatLink = (cutoff: string, banListId: number) => `/cards?cutoff=${cutoff}&banList=${banListId}`;

// The Cards page (YGO-38, was the Explorer): the whole catalog, or one format's pool, browsed with
// the same filters as a season's Pool tab; the official ban lists; and, for site admins, the
// catalog sync. Public, global data only. The format and filters live in the URL, so a link (e.g.
// from a season's Rules) opens the same view.
export default function CardsPage() {
  const summary = useApi<ExploreSummary>("/explore/summary");
  const { params, setParam, setParams, setList, clearFilters } = usePoolParams();
  const cutoff = params.get("cutoff") ?? "";
  const banList = params.get("banList") ?? "";
  const page = Number(params.get("page") ?? 0);

  const format = new URLSearchParams();
  if (cutoff) format.set("cutoff", cutoff);
  if (banList) format.set("banList", banList);
  const query = new URLSearchParams(poolQueryString(params, page, PAGE_SIZE));
  format.forEach((v, k) => query.set(k, v));

  const facets = useApi<PoolFacets>(`/explore/catalog/facets${cutoff ? `?cutoff=${cutoff}` : ""}`);
  const stats = useApi<FormatSummary>(`/explore/catalog/summary?${format.toString()}`);
  const cards = useApi<CatalogPage>(`/explore/catalog?${query.toString()}`);

  const lists = summary.data?.banLists ?? [];
  const chosenList = lists.find((b) => String(b.id) === banList) ?? null;
  const c = cards.data;
  const lastPage = c ? Math.max(0, Math.ceil(c.total / c.pageSize) - 1) : 0;

  return (
    <section className="cards-page">
      <h2>Cards</h2>
      <p className="muted lead">
        Every TCG card, or one format's pool: pick a cutoff and a ban list to see what a season with them would draft from.
      </p>

      <section className="panel format-panel">
        <div className="format-controls">
          <label><span className="label-text">TCG cutoff <InfoTip>Cards released in the TCG on or before this date. Empty: the whole catalog.</InfoTip></span>
            <input type="date" value={cutoff} onChange={(e) => setParam("cutoff", e.target.value)} />
          </label>
          <label><span className="label-text">Ban list</span>
            <select value={banList} onChange={(e) => setParam("banList", e.target.value)}>
              <option value="">None (up to 3 of anything)</option>
              {lists.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </label>
          {(cutoff || banList) && <button className="ghost" onClick={() => setParams({ cutoff: "", banList: "" })}>Whole catalog</button>}
        </div>

        {stats.data && <FormatStats s={stats.data} hasList={!!banList} />}
        {(cutoff || banList) && (
          <p className="muted small format-note">
            A preview{cutoff && <> of cards released by {fmtDay(cutoff)}</>}{chosenList && <> with {chosenList.name}</>}.
            A season's pool can differ: commissioners add exceptions and soft bans on its Pool tab.
          </p>
        )}

        {lists.length > 0 && (
          <BanListTable lists={lists} cutoff={cutoff} banList={banList}
                        onBrowse={(b) => setParams({ cutoff: b.effectiveDate, banList: String(b.id) })} />
        )}
      </section>

      <h3 className="cards-head">
        {chosenList || cutoff ? "Cards in this format" : "All cards"}
        {c && <span className="pill">{c.total.toLocaleString()}</span>}
      </h3>
      <PoolFilters key={`${params.get("q") ?? ""}|${params.get("cardText") ?? ""}`} params={params} setOne={setParam} setSome={setParams}
                   setList={setList} clear={clearFilters} facets={facets.data} hasBanList={!!banList} />
      {cards.error && <p className="bad">Couldn't load cards ({cards.error})</p>}
      {!c ? <Loading /> : c.items.length === 0 ? (
        <p className="muted">No cards match.</p>
      ) : (
        <>
          <CatalogGrid cards={c.items} />
          {c.total > c.pageSize && (
            <p className="pager">
              <button disabled={page === 0} onClick={() => setParam("page", String(page - 1))}>Previous</button>
              <span className="muted">Page {page + 1} of {lastPage + 1}</span>
              <button disabled={page >= lastPage} onClick={() => setParam("page", String(page + 1))}>Next</button>
            </p>
          )}
        </>
      )}

      {summary.data && <CatalogAdmin s={summary.data} />}
    </section>
  );
}

const SHOWN_LISTS = 6;      // newest first; the rest behind "Show all"
const FILTER_FROM = 10;     // a name/year filter once there are this many lists

// The official ban lists as a compact table, newest first (YGO-38: built for 20+ lists). The six
// newest show; "Show all" reveals the rest, and with many lists a filter matches name or year.
function BanListTable({ lists, cutoff, banList, onBrowse }: {
  lists: BanListInfo[]; cutoff: string; banList: string; onBrowse: (b: BanListInfo) => void;
}) {
  const [all, setAll] = useState(false);
  const [filter, setFilter] = useState("");
  const term = filter.trim().toLowerCase();
  const matching = term ? lists.filter((b) => b.name.toLowerCase().includes(term) || b.effectiveDate.includes(term)) : lists;
  const isActive = (b: BanListInfo) => String(b.id) === banList && cutoff === b.effectiveDate;
  // Collapsed: the newest few, plus the format being browsed if it's older (it stays in its place by date).
  const shown = all || term ? matching : matching.filter((b, i) => i < SHOWN_LISTS || isActive(b));

  return (
    <div className="banlist-table">
      <div className="lists-head">
        <h4>Official ban lists <span className="muted">{lists.length}</span></h4>
        {lists.length >= FILTER_FROM && (
          <input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter by name or year"
                 aria-label="Filter ban lists" />
        )}
      </div>
      {shown.length === 0 ? <p className="muted small">No list matches.</p> : (
        <table>
          <thead>
            <tr><th>List</th><th>Effective</th><th title="Forbidden · Limited · Semi-Limited">F · L · S</th><th /></tr>
          </thead>
          <tbody>
            {shown.map((b) => (
              <tr key={b.id} className={isActive(b) ? "active" : undefined}>
                <td><Link to={`/cards/banlists/${b.id}`}>{b.name}</Link></td>
                <td className="nowrap">{fmtDay(b.effectiveDate)}</td>
                <td>
                  <span className="banlist-counts">
                    <span className="badge ban-forbidden" title="Forbidden">{b.forbidden}</span>
                    <span className="badge ban-limited" title="Limited">{b.limited}</span>
                    <span className="badge ban-semi_limited" title="Semi-Limited">{b.semiLimited}</span>
                  </span>
                </td>
                <td className="actions">
                  {isActive(b)
                    ? <span className="pill accent">Browsing</span>
                    : <button className="sm" onClick={() => onBrowse(b)} title="Its own date as the cutoff, with this list">Browse format</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {!term && matching.length > SHOWN_LISTS && (
        <button className="link" onClick={() => setAll(!all)}>{all ? "Show fewer" : `Show all ${matching.length} lists`}</button>
      )}
    </div>
  );
}

function FormatStats({ s, hasList }: { s: FormatSummary; hasList: boolean }) {
  return (
    <div className="stat-tiles format-stats">
      <div className="stat-tile"><div className="stat-label">Cards</div><div className="stat-value">{s.cards.toLocaleString()}</div></div>
      <div className="stat-tile"><div className="stat-label">Main Deck monsters</div><div className="stat-value">{s.monsters.toLocaleString()}</div></div>
      <div className="stat-tile"><div className="stat-label">Extra Deck</div><div className="stat-value">{s.extra.toLocaleString()}</div></div>
      <div className="stat-tile"><div className="stat-label">Spells · Traps</div><div className="stat-value">{s.spells.toLocaleString()} · {s.traps.toLocaleString()}</div></div>
      {hasList && (
        <div className="stat-tile">
          <div className="stat-label">On the list <InfoTip>Listed cards that are in this format. A list can name cards released after the cutoff; those don't count.</InfoTip></div>
          <div className="stat-value banlist-counts">
            <span className="badge ban-forbidden" title="Forbidden">{s.forbidden}</span>
            <span className="badge ban-limited" title="Limited">{s.limited}</span>
            <span className="badge ban-semi_limited" title="Semi-Limited">{s.semiLimited}</span>
          </div>
        </div>
      )}
    </div>
  );
}

// Card tiles like the Pool tab's grid, linking to the card's catalog page.
function CatalogGrid({ cards }: { cards: CatalogCard[] }) {
  return (
    <ul className="pool-grid">
      {cards.map((c) => (
        <li key={c.cardId} className={c.banStatus === "FORBIDDEN" ? "unusable" : undefined}>
          <CardPreview cardId={c.cardId}>
            <CardImage passcode={c.passcode} size="small" alt="" className="tile-art" />
          </CardPreview>
          <div className="tile-name" title={c.name}><Link to={`/cards/${c.cardId}`}>{c.name}</Link></div>
          <div className="tile-stats">{cardStats(c)}</div>
          <div className="tile-badges">
            {c.banStatus && <span className={`badge ban-${c.banStatus.toLowerCase()}`}>{statusLabel[c.banStatus]}</span>}
            <span className="badge" title="TCG release">{c.tcgDate.slice(0, 4)}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

// Site admins only: the catalog's size and last sync, and a manual sync.
function CatalogAdmin({ s }: { s: ExploreSummary }) {
  const { session } = useSession();
  const [syncMsg, setSyncMsg] = useState<string | null>(null);
  if (!session?.user?.admin) return null;
  const syncNow = async () => {
    setSyncMsg("Syncing… (a few seconds)");
    try {
      const r = await apiPost<SyncResult>("/admin/catalog/sync");
      setSyncMsg(`Sync ${r.status.toLowerCase()}` + (r.cardsAdded != null ? `: ${r.cardsAdded} added, ${r.cardsUpdated} updated` : "") + (r.error ? ` (${r.error})` : ""));
    } catch (e) {
      setSyncMsg(`Sync failed (${(e as Error).message})`);
    }
  };
  return (
    <details className="panel admin-tools">
      <summary>Catalog admin <span className="badge">admin</span></summary>
      <p>
        {s.cards.toLocaleString()} cards · {s.sets.toLocaleString()} sets · {s.prints.toLocaleString()} prints
        <br />
        <span className="muted small">
          {s.lastSync
            ? `Last sync: ${s.lastSync.status.toLowerCase()} (${s.lastSync.trigger.toLowerCase()}) ${new Date(s.lastSync.startedAt).toLocaleString()}`
              + (s.lastSync.sourceVersion ? `, YGOPRODeck v${s.lastSync.sourceVersion}` : "") + (s.lastSync.error ? `: ${s.lastSync.error}` : "")
            : "No catalog sync yet. Run ./gradlew syncCatalog in backend/."}
        </span>
      </p>
      <p><button onClick={() => void syncNow()}>Sync catalog now</button> <span className="muted">{syncMsg}</span></p>
      {s.banLists.length === 0 && <p className="muted">No ban lists imported. Run ./gradlew importBanLists in backend/.</p>}
    </details>
  );
}
