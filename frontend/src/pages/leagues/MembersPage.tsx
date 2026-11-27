import { useState, type FormEvent } from "react";
import { apiDelete, apiPost, type InviteView, type LeagueMember } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import InviteList from "./InviteList";
import { useLeague } from "./LeagueLayout";
import { roleLabel } from "./roles";
import { useConfirm } from "../../components/Feedback";

// The league's group of people. The owner invites and removes people; who commissions or plays
// is chosen per season (on each season's Players tab).
export default function MembersPage() {
  const { league, reload } = useLeague();
  const isOwner = league.myRole === "OWNER";
  // Invites are owner-only; skip the request for everyone else.
  const invites = useApi<InviteView[]>(isOwner ? `/leagues/${league.id}/invites` : null);
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();

  // Runs a change, then refreshes what it affects. Errors (e.g. "is part of a season") show inline.
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

  const remove = async (m: LeagueMember) => {
    if (!(await confirm({ title: `Remove ${m.displayName} from ${league.name}?`, confirmLabel: "Remove", danger: true }))) return;
    void act(() => apiDelete(`/leagues/${league.id}/members/${m.id}`), { members: true, invites: true });
  };

  const claimLink = (m: LeagueMember) =>
    act(() => apiPost(`/leagues/${league.id}/invites`, { placeholderId: m.id, expiresInDays: 30 }), { invites: true });

  return (
    <>
      <h3>Members ({league.members.length})</h3>
      <p className="muted">
        Everyone in the league. Each season's commissioners and players are picked from here, and any member can follow
        every season.
      </p>
      {error && <p className="bad">{error}</p>}
      <table>
        <thead>
          <tr><th>Name</th><th />{isOwner && <th />}</tr>
        </thead>
        <tbody>
          {league.members.map((m) => (
            <tr key={m.id}>
              <td>
                {m.displayName}
                {m.you && <span className="badge">you</span>}
                {m.placeholder && <span className="muted"> · no account yet</span>}
              </td>
              <td>{m.role === "OWNER" && roleLabel.OWNER}</td>
              {isOwner && (
                <td className="actions">
                  {m.placeholder && <button className="sm" onClick={() => void claimLink(m)}>Create claim link</button>}
                  {m.role !== "OWNER" && <button className="danger sm" onClick={() => void remove(m)}>Remove</button>}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      {isOwner && (
        <>
          <AddPlaceholder leagueId={league.id} onAdded={reload} />
          <h3>Invites</h3>
          <p className="muted">
            Send a link to anyone you want in the league. Claim links hand a placeholder seat to whoever opens them.
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
      <h4>Add a placeholder</h4>
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
  const [days, setDays] = useState("7");
  const [maxUses, setMaxUses] = useState("");
  const [error, setError] = useState<string | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await apiPost(`/leagues/${leagueId}/invites`, {
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
        <button type="submit" className="primary">Create invite link</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </>
  );
}
