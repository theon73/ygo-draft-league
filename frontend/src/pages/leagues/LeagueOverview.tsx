import { useState, type FormEvent } from "react";
import { apiDelete, apiPatch, apiPost, type InviteView, type LeagueMember, type LeagueRole } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import InviteList from "./InviteList";
import { useLeague } from "./LeagueLayout";
import { roleLabel } from "./roles";

export default function LeagueOverview() {
  const { league, reload } = useLeague();
  const isCommissioner = league.myRole === "COMMISSIONER";
  // Invites are commissioner-only; skip the request for everyone else.
  const invites = useApi<InviteView[]>(isCommissioner ? `/leagues/${league.id}/invites` : null);
  const [error, setError] = useState<string | null>(null);

  // Runs a change, then refreshes what it affects. Errors (e.g. "needs at least one commissioner") show inline.
  const act = async (change: () => Promise<unknown>, after: { members?: boolean; invites?: boolean } = {}) => {
    setError(null);
    try {
      await change();
      if (after.members) reload();
      if (after.invites) invites.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const setRole = (m: LeagueMember, role: LeagueRole) =>
    act(() => apiPatch(`/leagues/${league.id}/members/${m.id}`, { role }), { members: true });

  const remove = (m: LeagueMember) => {
    if (!window.confirm(`Remove ${m.displayName} from ${league.name}?`)) return;
    void act(() => apiDelete(`/leagues/${league.id}/members/${m.id}`), { members: true, invites: true });
  };

  const claimLink = (m: LeagueMember) =>
    act(() => apiPost(`/leagues/${league.id}/invites`, { placeholderId: m.id, expiresInDays: 30 }), { invites: true });

  return (
    <>
      <h3>Members ({league.members.length})</h3>
      {error && <p className="bad">{error}</p>}
      <table>
        <thead>
          <tr><th>Name</th><th>Role</th>{isCommissioner && <th />}</tr>
        </thead>
        <tbody>
          {league.members.map((m) => (
            <tr key={m.id}>
              <td>
                {m.displayName}
                {m.you && <span className="badge">you</span>}
                {m.placeholder && <span className="muted"> · no account yet</span>}
              </td>
              <td>
                {isCommissioner ? (
                  <select value={m.role} onChange={(e) => void setRole(m, e.target.value as LeagueRole)} aria-label={`Role of ${m.displayName}`}>
                    {/* Placeholders can't be commissioners until someone claims the seat. */}
                    {(m.placeholder ? (["PLAYER", "VIEWER"] as const) : (["COMMISSIONER", "PLAYER", "VIEWER"] as const)).map((r) => (
                      <option key={r} value={r}>{roleLabel[r]}</option>
                    ))}
                  </select>
                ) : (
                  roleLabel[m.role]
                )}
              </td>
              {isCommissioner && (
                <td className="actions">
                  {m.placeholder && <button onClick={() => void claimLink(m)}>Create claim link</button>}
                  <button onClick={() => remove(m)}>Remove</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {isCommissioner && (
        <>
          <AddPlaceholder leagueId={league.id} onAdded={reload} />
          <h3>Invites</h3>
          <p className="muted">
            Send a link to anyone you want in the league. Player invites add a player; viewer invites give read-only access
            (the viewer allowlist). Claim links hand a placeholder seat to whoever opens them.
          </p>
          <CreateInvite leagueId={league.id} onCreated={invites.reload} />
          {invites.error && <p className="bad">Couldn't load invites ({invites.error})</p>}
          {invites.data && (
            <InviteList
              invites={invites.data}
              onRevoke={(code) => void act(() => apiDelete(`/leagues/${league.id}/invites/${code}`), { invites: true })}
            />
          )}
        </>
      )}
      <p className="muted">Created {new Date(league.createdAt).toLocaleDateString()}.</p>
    </>
  );
}

function AddPlaceholder({ leagueId, onAdded }: { leagueId: string; onAdded: () => void }) {
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await apiPost(`/leagues/${leagueId}/members/placeholders`, { displayName: name });
      setName("");
      onAdded();
    } catch (err) {
      setError((err as Error).message);
    }
  };
  return (
    <>
      <h4>Add a placeholder player</h4>
      <p className="muted">A named seat for someone without an account yet, so you can set up seasons now. Send them a claim link later.</p>
      <form onSubmit={submit}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" maxLength={40} required />
        <button type="submit" disabled={!name.trim()}>Add</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </>
  );
}

function CreateInvite({ leagueId, onCreated }: { leagueId: string; onCreated: () => void }) {
  const [role, setRole] = useState<LeagueRole>("PLAYER");
  const [days, setDays] = useState("7");
  const [maxUses, setMaxUses] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await apiPost(`/leagues/${leagueId}/invites`, {
        role,
        expiresInDays: days === "never" ? null : Number(days),
        maxUses: maxUses.trim() === "" ? null : Number(maxUses),
      });
      setMaxUses("");
      onCreated();
    } catch (err) {
      setError((err as Error).message);
    }
  };
  return (
    <>
      <form onSubmit={submit}>
        <select value={role} onChange={(e) => setRole(e.target.value as LeagueRole)} aria-label="Invite role">
          <option value="PLAYER">Player</option>
          <option value="VIEWER">Viewer (read-only)</option>
        </select>
        <select value={days} onChange={(e) => setDays(e.target.value)} aria-label="Expires">
          <option value="1">Expires in 1 day</option>
          <option value="7">Expires in 7 days</option>
          <option value="30">Expires in 30 days</option>
          <option value="never">Never expires</option>
        </select>
        <input
          className="narrow"
          value={maxUses}
          onChange={(e) => setMaxUses(e.target.value.replace(/\D/g, ""))}
          placeholder="Max uses (blank = unlimited)"
          inputMode="numeric"
        />
        <button type="submit">Create invite link</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </>
  );
}
