import type { Health } from "../api/client";
import { useApi } from "../hooks/useApi";

export default function Home() {
  const { data, error, loading } = useApi<Health>("/health");

  return (
    <section>
      <h2>Welcome</h2>
      <p>League hub, draft room, and transactions for the classic draft league.</p>
      <p className="status">
        Backend:{" "}
        {loading ? "checking…" : error ? <span className="bad">down ({error})</span> : <span className="good">{data!.status}</span>}
      </p>
    </section>
  );
}
