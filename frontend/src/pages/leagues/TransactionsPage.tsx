import { Link } from "react-router-dom";
import type { SeasonTransaction } from "../../api/client";
import { EmptyState, Loading } from "../../components/Feedback";
import PlayerAvatar from "../../components/PlayerAvatar";
import { useApi } from "../../hooks/useApi";
import CardChip from "./CardChip";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";

// The Transactions tab: the league's activity feed, newest first: free-agent claims and executed
// trades. For a trade, the entry is from the proposer's side (the other player got the reverse).
// YGO-34: each entry is a card with the player's avatar, what kind it was, and the cards with their
// art on both sides, instead of a text table.
export default function TransactionsPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const feed = useApi<SeasonTransaction[]>(`${base}/transactions`);
  const avatars = new Map(season.participants.map((p) => [p.memberId, p.avatar]));

  // Allowances the player received (or gave) in a trade, as their own chips after the cards.
  const allowances = (t: SeasonTransaction, received: boolean) =>
    t.allowances.filter((a) => (received ? a.toMemberId : a.fromMemberId) === t.memberId).map((a, i) => (
      <span key={`a${i}`} className="allowance-chip">
        + {a.quantity} {a.kind === "TRADE" ? "trade" : "free-agent claim"}{a.quantity === 1 ? "" : "s"} <span className="muted">(week {a.week})</span>
      </span>
    ));

  if (feed.error) return <p className="bad">Couldn't load the transactions ({feed.error})</p>;
  if (!feed.data) return <Loading />;
  return (
    <>
      <p className="muted small">
        {season.status === "ACTIVE"
          ? <>Claim from the <Link to="../rosters/free-agents">Free agents</Link>, or make offers on the <Link to="../trades">Trades</Link> tab.</>
          : season.status === "COMPLETE" ? "The season is over." : "Free agency opens when the draft is complete."}
      </p>
      {feed.data.length === 0 ? (
        <EmptyState title="No transactions yet"><p>Claims and completed trades show up here.</p></EmptyState>
      ) : (
        <ul className="tx-feed">
          {feed.data.map((t, i) => {
            const got = [...t.added.map((c) => <CardChip key={c.cardId} card={c} />), ...allowances(t, true)];
            const gave = [...t.dropped.map((c) => <CardChip key={c.cardId} card={c} />), ...allowances(t, false)];
            return (
              <li key={i} className="tx">
                <div className="tx-head">
                  <PlayerAvatar name={t.player} passcode={avatars.get(t.memberId)} size="sm" />
                  <Link to={`../rosters/${t.memberId}`} className="tx-player">{t.player}</Link>
                  {t.kind === "TRADE" ? (
                    <span className="badge">trade with <Link to={`../rosters/${t.counterpartyMemberId}`}>{t.counterparty}</Link></span>
                  ) : <span className="badge good">free-agent claim</span>}
                  {t.onBehalfBy && <span className="muted small">by {t.onBehalfBy}</span>}
                  <span className="tx-when muted small">
                    {t.week === 0 ? "Before week 1" : `Week ${t.week}`} · {new Date(t.at).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}
                  </span>
                </div>
                <div className="tx-body">
                  <div className="tx-side"><h6>{t.kind === "TRADE" ? "Got" : "Picked up"}</h6>{got.length ? got : <span className="muted small">Nothing</span>}</div>
                  <span className="tx-arrow" aria-hidden="true">⇄</span>
                  <div className="tx-side"><h6>{t.kind === "TRADE" ? "Gave" : "Dropped"}</h6>{gave.length ? gave : <span className="muted small">Nothing</span>}</div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
