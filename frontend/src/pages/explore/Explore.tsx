import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import type { CardPage, ExploreSummary } from "../../api/client";
import { useApi } from "../../hooks/useApi";

// Temporary read-only view of the global data (catalog + official ban lists) until the real
// pool builder exists. The search lives in the URL (?q=&page=) so Back and reload keep it.
export default function Explore() {
  const summary = useApi<ExploreSummary>("/explore/summary");
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const page = Number(params.get("page") ?? 0);
  const [draft, setDraft] = useState(q);
  const cards = useApi<CardPage>(`/explore/cards?q=${encodeURIComponent(q)}&page=${page}`);

  const go = (nextQ: string, nextPage: number) =>
    setParams(nextQ || nextPage ? { ...(nextQ && { q: nextQ }), ...(nextPage && { page: String(nextPage) }) } : {});

  const s = summary.data;
  const c = cards.data;
  const lastPage = c ? Math.max(0, Math.ceil(c.total / c.pageSize) - 1) : 0;

  return (
    <section>
      <h2>Explorer</h2>
      <p className="muted">A temporary read-only view of the card catalog and the official ban lists.</p>

      {summary.error && <p className="bad">Couldn't load summary ({summary.error})</p>}
      {s && (
        <>
          <p>
            {s.cards.toLocaleString()} cards · {s.sets.toLocaleString()} sets · {s.prints.toLocaleString()} prints
            <br />
            <span className="muted">
              {s.lastSync
                ? `Last sync: ${s.lastSync.status.toLowerCase()} (${s.lastSync.trigger.toLowerCase()}) ` +
                  `${new Date(s.lastSync.startedAt).toLocaleString()}` +
                  (s.lastSync.sourceVersion ? `, YGOPRODeck v${s.lastSync.sourceVersion}` : "") +
                  (s.lastSync.error ? `: ${s.lastSync.error}` : "")
                : "No catalog sync yet. Run ./gradlew syncCatalog in backend/."}
            </span>
          </p>

          <h3>Official ban lists</h3>
          {s.banLists.length === 0 ? (
            <p className="muted">None imported. Run ./gradlew importBanLists in backend/.</p>
          ) : (
            <table>
              <thead>
                <tr><th>List</th><th>Effective</th><th>Forbidden</th><th>Limited</th><th>Semi</th><th>Rev</th></tr>
              </thead>
              <tbody>
                {s.banLists.map((b) => (
                  <tr key={b.id}>
                    <td><Link to={`/explore/banlists/${b.id}`}>{b.name}</Link></td>
                    <td>{b.effectiveDate}</td>
                    <td>{b.forbidden}</td>
                    <td>{b.limited}</td>
                    <td>{b.semiLimited}</td>
                    <td>{b.revision}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}

      <h3>Cards</h3>
      <form onSubmit={(e) => { e.preventDefault(); go(draft.trim(), 0); }}>
        <input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Search by name" />
        <button type="submit">Search</button>
      </form>
      {cards.error && <p className="bad">Search failed ({cards.error})</p>}
      {c && (
        <>
          <p className="muted">
            {c.total.toLocaleString()} match{c.total === 1 ? "" : "es"}
            {c.total > c.pageSize && ` · page ${page + 1} of ${lastPage + 1}`}
          </p>
          <table>
            <thead>
              <tr><th>Name</th><th>Type</th><th>TCG release</th></tr>
            </thead>
            <tbody>
              {c.items.map((card) => (
                <tr key={card.id}>
                  <td><Link to={`/explore/cards/${card.id}`}>{card.name}</Link></td>
                  <td>{card.cardType}</td>
                  <td>{card.tcgDate}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {c.total > c.pageSize && (
            <p>
              <button disabled={page === 0} onClick={() => go(q, page - 1)}>Previous</button>{" "}
              <button disabled={page >= lastPage} onClick={() => go(q, page + 1)}>Next</button>
            </p>
          )}
        </>
      )}
    </section>
  );
}
