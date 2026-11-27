import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import CardImage from "../../components/CardImage";
import type { CardDetail } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import { statusLabel } from "./banStatus";
import { Loading } from "../../components/Feedback";

export default function CardView() {
  const { id } = useParams();
  const { data: c, error } = useApi<CardDetail>(`/explore/cards/${id}`);
  const [art, setArt] = useState(0);

  if (error) return <p className="bad">Couldn't load card ({error})</p>;
  if (!c) return <Loading />;

  const stats = [
    c.attribute,
    c.race,
    c.level != null && `Level ${c.level}`,
    c.rank != null && `Rank ${c.rank}`,
    c.linkRating != null && `Link ${c.linkRating}`,
    c.pendulumScale != null && `Scale ${c.pendulumScale}`,
    c.atk != null || c.def != null ? `ATK ${c.atk ?? "?"} / DEF ${c.def ?? "—"}` : null,
  ].filter(Boolean);

  return (
    <section>
      <p><Link to="/explore">← Explorer</Link></p>
      <h2>{c.name}</h2>
      <div className="card-art">
        <CardImage passcode={c.passcodes[Math.min(art, c.passcodes.length - 1)]} size="full" alt={c.name} />
        {c.passcodes.length > 1 && (
          <div className="alt-arts">
            {c.passcodes.map((p, i) => (
              <button key={p} className={i === art ? "selected" : ""} onClick={() => setArt(i)} title={`Artwork ${i + 1} (${p})`}>
                <CardImage passcode={p} size="small" alt={`${c.name}, artwork ${i + 1}`} />
              </button>
            ))}
          </div>
        )}
      </div>
      <p>
        {c.cardType} · {stats.join(" · ")}
        <br />
        <span className="muted">
          TCG release {c.tcgDate}
          {c.archetype && ` · ${c.archetype}`} · passcode{c.passcodes.length > 1 ? "s" : ""} {c.passcodes.join(", ")}
        </span>
      </p>
      <p className="card-text">{c.description}</p>

      <h3>Official ban lists</h3>
      {c.banStatuses.length === 0 ? (
        <p className="muted">Unlimited on every imported list.</p>
      ) : (
        <ul>
          {c.banStatuses.map((b) => (
            <li key={b.banListId}>
              <Link to={`/explore/banlists/${b.banListId}`}>{b.banList}</Link>: {statusLabel[b.status]}
            </li>
          ))}
        </ul>
      )}

      <h3>Prints ({c.prints.length})</h3>
      <table>
        <thead>
          <tr><th>Code</th><th>Set</th><th>Set date</th><th>Rarity</th></tr>
        </thead>
        <tbody>
          {c.prints.map((p) => (
            <tr key={`${p.printCode}|${p.setName}|${p.rarity}`}>
              <td>{p.printCode}</td>
              <td>{p.setName}</td>
              <td>{p.setDate ?? "—"}</td>
              <td>{p.rarity}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
