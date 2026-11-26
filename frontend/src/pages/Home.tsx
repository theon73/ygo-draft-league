import { Link } from "react-router-dom";
import type { Health } from "../api/client";
import { useApi } from "../hooks/useApi";

export default function Home() {
  const { data, error, loading } = useApi<Health>("/health");

  return (
    <section>
      <h2>Welcome</h2>
      <p>
        Run your own Yu-Gi-Oh draft league: pick a format and card pool, draft it with your friends, then trade,
        claim free agents and play out the season.
      </p>
      <p>
        Leagues aren't here yet. In the meantime, the <Link to="/explore">Explorer</Link> shows the card catalog and
        ban lists.
      </p>
      <p className="status">
        Backend:{" "}
        {loading ? "checking…" : error ? <span className="bad">down ({error})</span> : <span className="good">{data!.status}</span>}
      </p>
    </section>
  );
}
