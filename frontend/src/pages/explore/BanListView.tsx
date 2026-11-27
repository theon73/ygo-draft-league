import { Link, useParams } from "react-router-dom";
import type { BanListDetail, BanStatus } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { statusLabel } from "./banStatus";
import { Loading } from "../../components/Feedback";

const ORDER: BanStatus[] = ["FORBIDDEN", "LIMITED", "SEMI_LIMITED"];

export default function BanListView() {
  const { id } = useParams();
  const { data: b, error } = useApi<BanListDetail>(`/explore/banlists/${id}`);

  if (error) return <p className="bad">Couldn't load ban list ({error})</p>;
  if (!b) return <Loading />;

  return (
    <section>
      <p><Link to="/explore">← Explorer</Link></p>
      <h2>{b.info.name}</h2>
      <p className="muted">
        Effective {b.info.effectiveDate} · revision {b.info.revision}
        {b.note && ` · ${b.note}`}
      </p>
      {ORDER.map((status) => {
        const cards = b.entries.filter((e) => e.status === status);
        return (
          <div key={status}>
            <h3>{statusLabel[status]} ({cards.length})</h3>
            <ul className="columns">
              {cards.map((e) => (
                <li key={e.cardId}><Link to={`/explore/cards/${e.cardId}`}>{e.name}</Link></li>
              ))}
            </ul>
          </div>
        );
      })}
    </section>
  );
}
