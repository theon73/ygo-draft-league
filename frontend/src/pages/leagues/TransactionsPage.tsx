import { Link } from "react-router-dom";
import type { CardRef, SeasonTransaction } from "../../api/client";
import CardPreview from "../../components/CardPreview";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonCardPath } from "./RostersPage";
import { useSeason } from "./SeasonLayout";

// The Transactions tab: the league's activity feed, newest first: free-agent claims and executed
// trades. For a trade, the row is from the proposer's side (the other player got the reverse).
export default function TransactionsPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const feed = useApi<SeasonTransaction[]>(`${base}/transactions`);

  const card = (c: CardRef) => (
    <CardPreview key={c.cardId} cardId={c.cardId}><Link to={seasonCardPath(league.id, season.id, c.cardId)}>{c.name}</Link></CardPreview>
  );
  // Allowances the player received (or gave) in a trade, after its cards.
  const allowances = (t: SeasonTransaction, received: boolean) => {
    const mine = t.allowances.filter((a) => (received ? a.toMemberId : a.fromMemberId) === t.memberId);
    if (mine.length === 0) return null;
    const text = mine.map((a) => `${a.quantity} ${a.kind === "TRADE" ? "trade" : "free-agent claim"}${a.quantity === 1 ? "" : "s"} (week ${a.week})`).join(", ");
    return <>{(received ? t.added : t.dropped).length > 0 && ", "}{text}</>;
  };
  const list = (cards: CardRef[]) => cards.map((c, i) => <span key={c.cardId}>{i > 0 && ", "}{card(c)}</span>);

  return (
    <>
      <p className="muted">
        {season.status === "ACTIVE"
          ? <>Claim from the <Link to="../rosters/free-agents">Free agents</Link> list, or make offers on the <Link to="../trades">Trades</Link> tab.</>
          : "Free agency opens when the draft is complete."}
      </p>
      {feed.error && <p className="bad">Couldn't load the transactions ({feed.error})</p>}
      {feed.data && (feed.data.length === 0 ? (
        <p className="muted">No transactions yet.</p>
      ) : (
        <table>
          <thead>
            <tr><th>When</th><th>Player</th><th>Received</th><th>Gave</th></tr>
          </thead>
          <tbody>
            {feed.data.map((t, i) => (
              <tr key={i}>
                <td>
                  {t.week === 0 ? "Before week 1" : `Week ${t.week}`}
                  <br /><span className="muted">{new Date(t.at).toLocaleString()}</span>
                </td>
                <td>
                  <Link to={`../rosters/${t.memberId}`}>{t.player}</Link>
                  {t.kind === "TRADE" && t.counterpartyMemberId && (
                    <> traded with <Link to={`../rosters/${t.counterpartyMemberId}`}>{t.counterparty}</Link></>
                  )}
                  {t.kind === "FREE_AGENCY" && <span className="muted"> · free agency</span>}
                  {t.onBehalfBy && <><br /><span className="muted">by {t.onBehalfBy}</span></>}
                </td>
                <td>{list(t.added)}{allowances(t, true)}</td>
                <td>{list(t.dropped)}{allowances(t, false)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </>
  );
}
