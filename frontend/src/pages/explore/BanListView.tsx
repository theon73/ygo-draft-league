import { Link, useParams } from "react-router-dom";
import type { BanChange, BanListDetail, BanStatus, ExploreSummary } from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { Loading } from "../../components/Feedback";
import { useApi } from "../../hooks/useApi";
import { statusLabel } from "./banStatus";
import { fmtDay, formatLink } from "./Explore";

const ORDER: BanStatus[] = ["FORBIDDEN", "LIMITED", "SEMI_LIMITED"];
const label = (s: BanStatus | null) => (s ? statusLabel[s] : "Unlimited");

// An official ban list (YGO-38): what changed since the list before it, then every card on it,
// each with its art (hover for the card).
export default function BanListView() {
  const { id } = useParams();
  const { data: b, error } = useApi<BanListDetail>(`/explore/banlists/${id}`);
  // For older / newer links: the official lists, newest first.
  const lists = useApi<ExploreSummary>("/explore/summary").data?.banLists ?? [];

  if (error) return <p className="bad">Couldn't load ban list ({error})</p>;
  if (!b) return <Loading />;

  // Changes grouped by where the card ended up: now Forbidden, Limited, Semi-Limited, then off the list.
  const destinations: (BanStatus | null)[] = [...ORDER, null];

  const at = lists.findIndex((l) => l.id === b.info.id);
  const newer = at > 0 ? lists[at - 1] : null;
  const older = at >= 0 && at < lists.length - 1 ? lists[at + 1] : null;

  return (
    <section className="banlist-page">
      <nav className="banlist-nav">
        <Link to="/cards">← Cards</Link>
        <span className="banlist-steps">
          {older && <Link to={`/cards/banlists/${older.id}`} title={older.name}>‹ Older: {older.name}</Link>}
          {newer && <Link to={`/cards/banlists/${newer.id}`} title={newer.name}>Newer: {newer.name} ›</Link>}
        </span>
      </nav>
      <div className="banlist-head">
        <div>
          <h2>{b.info.name}</h2>
          <p className="muted">
            Effective {fmtDay(b.info.effectiveDate)}
            {b.info.revision > 1 && ` · revision ${b.info.revision}`}
            {b.note && ` · ${b.note}`}
          </p>
        </div>
        <Link className="button" to={formatLink(b.info.effectiveDate, b.info.id)}>Browse this format</Link>
      </div>
      <p className="banlist-counts big">
        <span className="badge ban-forbidden">{b.info.forbidden} Forbidden</span>
        <span className="badge ban-limited">{b.info.limited} Limited</span>
        <span className="badge ban-semi_limited">{b.info.semiLimited} Semi-Limited</span>
      </p>

      {b.previous && (
        <section className="panel banlist-changes">
          <h3>Changes since <Link to={`/cards/banlists/${b.previous.id}`}>{b.previous.name}</Link>
            <span className="pill">{b.changes.length}</span></h3>
          {b.changes.length === 0 ? <p className="muted">No changes.</p> : destinations.map((to) => {
            const moved = b.changes.filter((c) => c.to === to);
            if (moved.length === 0) return null;
            return (
              <div key={to ?? "UNLIMITED"}>
                <h5>{to ? `Now ${statusLabel[to]}` : "Now unlimited"} <span className="muted">{moved.length}</span></h5>
                <ul className="banlist-cards-list">
                  {moved.map((c) => <ChangeLine key={c.cardId} c={c} />)}
                </ul>
              </div>
            );
          })}
        </section>
      )}

      {ORDER.map((status) => {
        const cards = b.entries.filter((e) => e.status === status);
        if (cards.length === 0) return null;
        return (
          <div key={status}>
            <h3>{statusLabel[status]} <span className="muted">{cards.length}</span></h3>
            <ul className="banlist-cards-list">
              {cards.map((e) => (
                <li key={e.cardId}><CardLine cardId={e.cardId} name={e.name} passcode={e.passcode} /></li>
              ))}
            </ul>
          </div>
        );
      })}
    </section>
  );
}

function ChangeLine({ c }: { c: BanChange }) {
  return (
    <li>
      <CardLine cardId={c.cardId} name={c.name} passcode={c.passcode} />
      <span className="muted small was">was {label(c.from)}</span>
    </li>
  );
}

function CardLine({ cardId, name, passcode }: { cardId: number; name: string; passcode: number }) {
  return (
    <CardPreview cardId={cardId}>
      <span className="roster-card">
        <CardImage passcode={passcode} size="small" alt="" className="chip-art" />
        <Link to={`/cards/${cardId}`}>{name}</Link>
      </span>
    </CardPreview>
  );
}
