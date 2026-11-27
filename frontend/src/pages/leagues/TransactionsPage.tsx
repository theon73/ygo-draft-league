import { Link } from "react-router-dom";
import type { CardRef, SeasonTransaction } from "../../api/client";
import CardPreview from "../../components/CardPreview";
import { useApi } from "../../hooks/useApi";
import { useLeague } from "./LeagueLayout";
import { seasonCardPath } from "./RostersPage";
import { useSeason } from "./SeasonLayout";

// The Transactions tab: the league's activity feed, newest first. Free-agent claims for now;
// trades join it in YGO-24.
export default function TransactionsPage() {
  const { league } = useLeague();
  const { season } = useSeason();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const feed = useApi<SeasonTransaction[]>(`${base}/transactions`);

  const card = (c: CardRef) => (
    <CardPreview key={c.cardId} cardId={c.cardId}><Link to={seasonCardPath(league.id, season.id, c.cardId)}>{c.name}</Link></CardPreview>
  );
  const list = (cards: CardRef[]) => cards.map((c, i) => <span key={c.cardId}>{i > 0 && ", "}{card(c)}</span>);

  return (
    <>
      <h4>Transactions</h4>
      <p className="muted">
        {season.status === "ACTIVE"
          ? <>Claim free agents from the <Link to="../rosters/free-agents">Free agents</Link> list. Trades are coming next.</>
          : "Free agency opens when the draft is complete."}
      </p>
      {feed.error && <p className="bad">Couldn't load the transactions ({feed.error})</p>}
      {feed.data && (feed.data.length === 0 ? (
        <p className="muted">No transactions yet.</p>
      ) : (
        <table>
          <thead>
            <tr><th>When</th><th>Player</th><th>Added</th><th>Dropped</th></tr>
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
                  {t.onBehalfBy && <><br /><span className="muted">by {t.onBehalfBy}</span></>}
                </td>
                <td>{list(t.added)}</td>
                <td>{list(t.dropped)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
    </>
  );
}
