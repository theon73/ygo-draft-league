import { useLeague } from "./LeagueLayout";
import { roleLabel } from "./roles";

export default function LeagueOverview() {
  const league = useLeague();
  return (
    <>
      <h3>Members ({league.members.length})</h3>
      <table>
        <thead>
          <tr><th>Name</th><th>Role</th></tr>
        </thead>
        <tbody>
          {league.members.map((m) => (
            <tr key={m.displayName}>
              <td>
                {m.displayName}
                {m.you && <span className="badge">you</span>}
                {m.placeholder && <span className="muted"> · no account yet</span>}
              </td>
              <td>{roleLabel[m.role]}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">Created {new Date(league.createdAt).toLocaleDateString()}. Invites and seasons are coming next.</p>
    </>
  );
}
