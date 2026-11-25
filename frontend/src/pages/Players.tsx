import type { Player } from "../api/client";
import { useApi } from "../hooks/useApi";

export default function Players() {
  const { data, error, loading } = useApi<Player[]>("/players");

  if (loading) return <p>Loading…</p>;
  if (error) return <p className="bad">Couldn't load players: {error}</p>;

  return (
    <section>
      <h2>Players</h2>
      <ul>
        {data!.map((p) => (
          <li key={p.id}>{p.displayName}</li>
        ))}
      </ul>
    </section>
  );
}
