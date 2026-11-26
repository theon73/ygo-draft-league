import { useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  apiDelete, apiGet, apiPatch, apiPost,
  type PoolCandidate, type PoolCard, type PoolCardPage, type PoolChange, type PoolFacets, type PoolSummary,
} from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { cardStats } from "../../components/cardStats";
import { statusLabel } from "../explore/banStatus";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import PoolFilters from "./PoolFilters";
import PoolGrid from "./PoolGrid";
import { activeFilterCount, MONSTER_ONLY, poolQueryString, type MultiParam } from "./poolQuery";

type Show = "ALL" | "EXCEPTIONS" | "FLAGGED" | "BAN_LIST" | "REMOVED";
const SHOWS: { value: Show; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "EXCEPTIONS", label: "Exceptions" },
  { value: "BAN_LIST", label: "On ban list" },
  { value: "FLAGGED", label: "Flagged" },
  { value: "REMOVED", label: "Removed" },
];

type View = "grid" | "table";
const VIEW_KEY = "poolView";
// The same standard options in both views; each view still remembers its own choice.
const PAGE_SIZES: Record<View, number[]> = { grid: [10, 25, 50, 100], table: [10, 25, 50, 100] };
const DEFAULT_PAGE_SIZE: Record<View, number> = { grid: 50, table: 50 };
const pageSizeKey = (v: View) => `poolPageSize.${v}`;

function savedPageSize(v: View): number {
  try {
    const n = Number(localStorage.getItem(pageSizeKey(v)));
    return PAGE_SIZES[v].includes(n) ? n : DEFAULT_PAGE_SIZE[v];
  } catch {
    return DEFAULT_PAGE_SIZE[v];
  }
}

// Per-viewer convenience only, so a missing or blocked localStorage just means the default.
function savedView(): View | null {
  try {
    const v = localStorage.getItem(VIEW_KEY);
    return v === "grid" || v === "table" ? v : null;
  } catch {
    return null;
  }
}

// The copy limit the ban list sets; a league override (maxPlayable) wins over it.
const listLimit = (c: PoolCard) => (c.banStatus === "FORBIDDEN" ? 0 : c.banStatus === "LIMITED" ? 1 : c.banStatus === "SEMI_LIMITED" ? 2 : 3);

export default function SeasonPoolPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}/pool`;
  const summary = useApi<PoolSummary>(base);
  const facets = useApi<PoolFacets>(`${base}/facets`, summary.data ? `${summary.data.total}-${summary.data.generatedAt}` : undefined);
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<View | null>(savedView);
  const page = Number(params.get("page") ?? 0);
  const show = (params.get("show") ?? "ALL") as Show;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Mid-season, every change takes effect from a week (never earlier than now).
  const [week, setWeek] = useState<string>("");

  // Commissioners curate in the table; everyone else browses the grid. A choice is remembered.
  const shownView: View = view ?? (summary.data?.canManage ? "table" : "grid");
  // Remembered per view, so the grid and the table each keep their own choice.
  const [pageSizes, setPageSizes] = useState<Record<View, number>>(() => ({ grid: savedPageSize("grid"), table: savedPageSize("table") }));
  const pageSize = pageSizes[shownView];
  const cards = useApi<PoolCardPage>(summary.data?.generatedAt ? `${base}/cards?${poolQueryString(params, page, pageSize)}` : null);

  if (summary.error) return <p className="bad">Couldn't load the pool ({summary.error})</p>;
  if (!summary.data) return <p className="muted">Loading…</p>;
  const s = summary.data;
  const manage = s.canManage;
  const effective: { effectiveFromWeek?: number } = s.started ? { effectiveFromWeek: Number(week || s.minEffectiveWeek) } : {};

  // Updaters build on the latest URL (functional form), so two changes in one event both stick.
  const setParam = (name: string, value: string) => {
    setParams((prev) => {
      const next = new URLSearchParams(prev);
      if (value) next.set(name, value); else next.delete(name);
      if (name !== "page") next.delete("page");   // a new filter starts from the first page
      return next;
    }, { replace: name !== "page" && name !== "show" });
  };
  const setList = (name: MultiParam, values: string[]) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    next.delete(name);
    for (const v of values) next.append(name, v);
    next.delete("page");
    if (name === "kind" && values.length > 0) {
      // Filters for kinds no longer shown would only empty the list, so drop them.
      if (!values.includes("MONSTER")) for (const p of MONSTER_ONLY) next.delete(p);
      if (!values.includes("SPELL")) next.delete("spellType");
      if (!values.includes("TRAP")) next.delete("trapType");
    }
    return next;
  }, { replace: true });
  const clearFilters = () => {
    const next = new URLSearchParams();
    if (params.get("show")) next.set("show", params.get("show")!);
    setParams(next);
  };
  const choosePageSize = (n: number) => {
    setPageSizes((sizes) => ({ ...sizes, [shownView]: n }));
    try { localStorage.setItem(pageSizeKey(shownView), String(n)); } catch { /* fine: not remembered */ }
    setParam("page", "");   // back to page 1: the current page may not exist any more
  };
  const chooseView = (v: View) => {
    setView(v);
    try { localStorage.setItem(VIEW_KEY, v); } catch { /* fine: not remembered */ }
    setParam("page", "");
  };

  const act = async (change: () => Promise<unknown>) => {
    setError(null);
    setBusy(true);
    try {
      await change();
      summary.reload();
      cards.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const generate = () => act(() => apiPost(`${base}/generate`));
  const setFlags = (c: PoolCard, flags: Partial<Pick<PoolCard, "softBanned" | "requiresOwnership">> & { maxPlayable?: number; clearMaxPlayable?: boolean }) =>
    act(() => apiPatch(`${base}/cards/${c.cardId}`, { ...flags, ...effective }));
  const remove = (c: PoolCard) => {
    if (!window.confirm(`Remove ${c.name} from the pool?`)) return;
    void act(() => apiDelete(`${base}/cards/${c.cardId}`, effective));
  };
  // Re-adding goes through the normal add: within the cutoff it's a dated card again, past it an exception.
  const readd = (c: PoolCard) => {
    const when = s.started ? ` from week ${effective.effectiveFromWeek}` : "";
    if (!window.confirm(`Put ${c.name} back in the pool${when}?`)) return;
    void act(() => apiPost(`${base}/cards`, { cardId: c.cardId, reason: c.reason, ...effective }));
  };

  const stale = s.rulesCutoff && s.generatedCutoff && s.rulesCutoff !== s.generatedCutoff;
  const lastPage = cards.data ? Math.max(0, Math.ceil(cards.data.total / cards.data.pageSize) - 1) : 0;
  const hasFilters = activeFilterCount(params) > 0 || params.getAll("kind").length > 0 || !!params.get("q");

  return (
    <>
      <h4>Card pool</h4>
      {s.generatedAt === null ? (
        <p className="muted">
          {s.rulesCutoff
            ? `No pool yet. Generating takes every card released in the TCG on or before ${s.rulesCutoff} (tokens excluded).`
            : <>Set the TCG cutoff on the <Link to="../rules">Rules</Link> tab first; the pool is generated from it.</>}
        </p>
      ) : (
        <p>
          {s.total.toLocaleString()} cards: {s.dated.toLocaleString()} up to the {s.generatedCutoff} cutoff, {s.exceptions} exception{s.exceptions === 1 ? "" : "s"}.{" "}
          <span className="muted">
            {s.softBanned} soft-banned · {s.requiresOwnership} must be owned · {s.overridden} with a league copy limit · {s.removed} removed
          </span>
          <br />
          <span className="muted">
            {s.banList
              ? `Ban list: ${s.banList} (${s.forbidden} forbidden, ${s.limited} limited, ${s.semiLimited} semi-limited in this pool). Copy limits follow it unless ${manage ? "you set one here" : "the league sets one"}.`
              : `No ban list in the rules, so every card is limited to 3 unless ${manage ? "you set a limit here" : "the league sets one"}.`}
          </span>
        </p>
      )}
      {stale && <p className="bad">The rules' cutoff is now {s.rulesCutoff}, but the pool was generated from {s.generatedCutoff}. Regenerate to match.</p>}
      {manage && !s.started && s.rulesCutoff && (
        <p>
          <button onClick={() => void generate()} disabled={busy}>{s.generatedAt ? "Regenerate from the rules" : "Generate pool"}</button>{" "}
          <span className="muted">
            {s.generatedAt ? "Exceptions and cards you removed are kept. " : ""}Thumbnails are fetched in the background afterwards.
          </span>
        </p>
      )}
      {manage && s.started && (
        <p>
          <label className="inline-label">Changes take effect from week{" "}
            <input type="number" className="narrow-num" min={s.minEffectiveWeek} value={week || s.minEffectiveWeek}
                   onChange={(e) => setWeek(e.target.value)} />
          </label>{" "}
          <span className="muted">The season has started: earlier weeks keep the pool they had.</span>
        </p>
      )}
      {error && <p className="bad">{error}</p>}

      {manage && <AddException base={base} effective={effective} onAdded={() => { summary.reload(); cards.reload(); }} />}
      {manage && s.total > 0 && <ArchetypeFlags base={base} effective={effective} onDone={() => { summary.reload(); cards.reload(); }} />}

      {s.generatedAt !== null && (
        <>
          <div className="pool-toolbar">
            <nav className="tabs small" aria-label="Show">
              {SHOWS.filter((x) => manage || x.value !== "REMOVED").map((x) => (
                <button key={x.value} className={x.value === show ? "active" : ""} onClick={() => setParam("show", x.value === "ALL" ? "" : x.value)}>
                  {x.label}
                </button>
              ))}
            </nav>
            <nav className="tabs small view-toggle" aria-label="View">
              <button className={shownView === "grid" ? "active" : ""} onClick={() => chooseView("grid")}>Grid</button>
              <button className={shownView === "table" ? "active" : ""} onClick={() => chooseView("table")}>Table</button>
            </nav>
          </div>
          <PoolFilters key={`${params.get("q") ?? ""}|${params.get("cardText") ?? ""}`} params={params} setOne={setParam} setList={setList} clear={clearFilters}
                       facets={facets.data} hasBanList={!!s.banList} />
          {cards.error && <p className="bad">Couldn't load cards ({cards.error})</p>}
          {cards.data && (
            <>
              <div className="results-bar">
                <span className="muted">
                  {cards.data.total.toLocaleString()} card{cards.data.total === 1 ? "" : "s"}
                  {hasFilters && " match"}
                  {cards.data.total > cards.data.pageSize && ` · page ${page + 1} of ${lastPage + 1}`}
                </span>
                <label className="per-page muted">
                  Per page{" "}
                  <select value={pageSize} onChange={(e) => choosePageSize(Number(e.target.value))} aria-label="Cards per page">
                    {PAGE_SIZES[shownView].map((n) => <option key={n} value={n}>{n}</option>)}
                  </select>
                </label>
              </div>
              {shownView === "grid" ? (
                <PoolGrid cards={cards.data.items} />
              ) : (
              <table className="pool">
                <thead>
                  <tr><th /><th>Card</th><th>Released</th><th>Soft-ban</th><th>Must own</th><th>Max copies</th>{manage && <th />}</tr>
                </thead>
                <tbody>
                  {cards.data.items.map((c) => (
                    <tr key={c.cardId}>
                      <td className="thumb"><CardPreview cardId={c.cardId}><CardImage passcode={c.passcode} size="small" alt="" /></CardPreview></td>
                      <td>
                        <CardPreview cardId={c.cardId}><Link to={`/explore/cards/${c.cardId}`}>{c.name}</Link></CardPreview>
                        {c.source === "EXCEPTION" && <span className="badge">exception</span>}
                        {c.banStatus && <span className={`badge ban-${c.banStatus.toLowerCase()}`}>{statusLabel[c.banStatus]}</span>}
                        <br />
                        <span className="muted">
                          {cardStats(c)}
                          {c.archetype && ` · ${c.archetype}`}
                          {c.reason && ` · ${c.reason}`}
                          {c.addedInWeek > 0 && ` · added week ${c.addedInWeek}`}
                          {c.removedInWeek !== null && (c.removedInWeek === 0 ? " · removed in setup" : ` · removed week ${c.removedInWeek}`)}
                        </span>
                      </td>
                      <td>{c.tcgDate}</td>
                      {manage && show !== "REMOVED" ? (
                        <>
                          <td><input type="checkbox" checked={c.softBanned} disabled={busy} aria-label={`Soft-ban ${c.name}`}
                                     onChange={(e) => void setFlags(c, { softBanned: e.target.checked })} /></td>
                          <td><input type="checkbox" checked={c.requiresOwnership} disabled={busy} aria-label={`${c.name} must be owned`}
                                     onChange={(e) => void setFlags(c, { requiresOwnership: e.target.checked })} /></td>
                          <td>
                            <select value={c.maxPlayable ?? ""} disabled={busy} aria-label={`Max playable copies of ${c.name}`}
                                    onChange={(e) => void setFlags(c, e.target.value === "" ? { clearMaxPlayable: true } : { maxPlayable: Number(e.target.value) })}>
                              <option value="">{c.banStatus ? `Ban list (${listLimit(c)})` : `Normal (3)`}</option>
                              {[3, 2, 1, 0].map((n) => <option key={n} value={n}>{n} (league rule)</option>)}
                            </select>
                          </td>
                          <td className="actions"><button onClick={() => remove(c)} disabled={busy}>Remove</button></td>
                        </>
                      ) : (
                        <>
                          <td>{c.softBanned ? "Yes" : ""}</td>
                          <td>{c.requiresOwnership ? "Yes" : ""}</td>
                          <td>{c.effectiveMax}{c.maxPlayable !== null && <span className="muted"> (league rule)</span>}</td>
                          {manage && (
                            <td className="actions"><button onClick={() => readd(c)} disabled={busy}>Re-add</button></td>
                          )}
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
              )}
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
      <ChangeLog base={base} version={`${s.total}-${s.removed}-${s.softBanned}-${s.generatedAt}`} />
    </>
  );
}

function AddException({ base, effective, onAdded }: { base: string; effective: object; onAdded: () => void }) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<PoolCandidate[] | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const find = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      setResults(await apiGet<PoolCandidate[]>(`${base}/candidates?q=${encodeURIComponent(q)}`));
    } catch (err) {
      setError((err as Error).message);
    }
  };
  const add = async (c: PoolCandidate) => {
    setError(null);
    try {
      await apiPost(`${base}/cards`, { cardId: c.cardId, reason: reason || null, ...effective });
      setResults((r) => r?.map((x) => (x.cardId === c.cardId ? { ...x, inPool: true } : x)) ?? null);
      onAdded();
    } catch (err) {
      setError((err as Error).message);
    }
  };

  return (
    <details className="panel">
      <summary>Add cards by hand (exceptions)</summary>
      <p className="muted">
        Cards on top of the dated pool, e.g. just past the cutoff to help a deck or a struggling roster. The reason is shown to the league.
      </p>
      <form onSubmit={find}>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Card name (2+ letters)" />
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason (optional)" maxLength={200} />
        <button type="submit" disabled={q.trim().length < 2}>Find</button>
      </form>
      {error && <p className="bad">{error}</p>}
      {results && (results.length === 0 ? <p className="muted">No cards match.</p> : (
        <table>
          <tbody>
            {results.map((c) => (
              <tr key={c.cardId}>
                <td className="thumb"><CardImage passcode={c.passcode} size="small" alt="" /></td>
                <td>{c.name}<br /><span className="muted">{c.cardType} · released {c.tcgDate}</span></td>
                <td className="actions">{c.inPool ? <span className="muted">in the pool</span> : <button onClick={() => void add(c)}>Add</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </details>
  );
}

function ArchetypeFlags({ base, effective, onDone }: { base: string; effective: object; onDone: () => void }) {
  const archetypes = useApi<{ archetype: string; cards: number }[]>(`${base}/archetypes`);
  const [archetype, setArchetype] = useState("");
  const [error, setError] = useState<string | null>(null);
  const apply = async (flags: object, what: string) => {
    if (!archetype || !window.confirm(`${what} every ${archetype} card in the pool?`)) return;
    setError(null);
    try {
      await apiPatch(`${base}/archetypes/${encodeURIComponent(archetype)}`, { ...flags, ...effective });
      onDone();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <details className="panel">
      <summary>Flag a whole archetype</summary>
      <select value={archetype} onChange={(e) => setArchetype(e.target.value)} aria-label="Archetype">
        <option value="">Choose an archetype…</option>
        {archetypes.data?.map((a) => <option key={a.archetype} value={a.archetype}>{a.archetype} ({a.cards})</option>)}
      </select>{" "}
      <button disabled={!archetype} onClick={() => void apply({ softBanned: true }, "Soft-ban")}>Soft-ban all</button>{" "}
      <button disabled={!archetype} onClick={() => void apply({ softBanned: false }, "Lift the soft ban on")}>Lift soft ban</button>
      {error && <p className="bad">{error}</p>}
    </details>
  );
}

function ChangeLog({ base, version }: { base: string; version: string }) {
  const changes = useApi<PoolChange[]>(`${base}/changes`, version);
  if (!changes.data || changes.data.length === 0) return null;
  const describe = (c: PoolChange) => {
    switch (c.kind) {
      case "GENERATED": return `Generated from the ${String(c.detail.cutoff)} cutoff (+${String(c.detail.added)}, −${String(c.detail.dropped)}, ${String(c.detail.total)} total)`;
      case "ADDED": return `Added ${c.card}${c.detail.source === "EXCEPTION" ? " as an exception" : ""}`;
      case "REMOVED": return `Removed ${c.card}`;
      case "FLAGS": {
        const what = Object.entries(c.detail).filter(([k]) => !["cards", "archetype"].includes(k)).map(([k, v]) => `${k} ${String(v)}`).join(", ");
        return `${c.card ?? `${String(c.detail.archetype)} (${String(c.detail.cards)} cards)`}: ${what}`;
      }
    }
  };
  return (
    <details>
      <summary>Pool history ({changes.data.length})</summary>
      <ul>
        {changes.data.map((c, i) => (
          <li key={i}>
            {describe(c)}
            {c.effectiveFromWeek > 0 && `, from week ${c.effectiveFromWeek}`}
            {c.reason && ` (${c.reason})`}
            <span className="muted"> · {c.by ?? "system"}, {new Date(c.at).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}
