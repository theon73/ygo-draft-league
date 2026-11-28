import InfoTip from "../../components/InfoTip";
import { useState, type FormEvent } from "react";
import { apiDelete, apiPost, apiPut, inviteLink, type InviteView, type LeagueMember } from "../../api/client";
import { useApi } from "../../hooks/useApi";
import InviteList, { copyText } from "./InviteList";
import { useLeague } from "./LeagueLayout";
import { roleLabel } from "./roles";
import { useConfirm, useToast } from "../../components/Feedback";
import AvatarPicker from "../../components/AvatarPicker";
import PlayerAvatar from "../../components/PlayerAvatar";

// The league's group of people. The owner invites and removes people; who commissions or plays
// is chosen per season (on each season's Players tab).
//
// Layout (YGO-38): the people list, and beside it (below on a phone) the owner's "Invite people"
// panel: invite links, then placeholders. Each placeholder row offers its own claim link.
export default function MembersPage() {
  const { league, reload } = useLeague();
  const isOwner = league.myRole === "OWNER";
  // Invites are owner-only; skip the request for everyone else.
  const invites = useApi<InviteView[]>(isOwner ? `/leagues/${league.id}/invites` : null);
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();
  const toast = useToast();
  const [picking, setPicking] = useState<LeagueMember | null>(null);

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

  // A placeholder's claim link: the active one if there is one, else a new one (30 days). Copied either way.
  const claimFor = (m: LeagueMember) => invites.data?.find((i) => i.kind === "CLAIM" && i.seatId === m.id) ?? null;
  const copyClaimLink = (m: LeagueMember) => act(async () => {
    const code = claimFor(m)?.code
      ?? (await apiPost<InviteView>(`/leagues/${league.id}/invites`, { placeholderId: m.id, expiresInDays: 30 })).code;
    await copyText(inviteLink(code));
    toast(`Claim link for ${m.displayName} copied. Whoever opens it takes over this seat.`);
  }, { invites: true });

  const accounts = league.members.filter((m) => !m.placeholder).length;
  const placeholders = league.members.length - accounts;

  return (
    <>
      <div className="section-head members-head">
        <h3>Members</h3>
        <span className="pill">{league.members.length} {league.members.length === 1 ? "member" : "members"}</span>
        {placeholders > 0 && <span className="pill">{placeholders} without an account</span>}
        <InfoTip>
          Everyone in the league. Each season's commissioners and players are picked from here (on the season's Players tab),
          and any member can follow every season.
        </InfoTip>
        {isOwner && <a className="button sm primary members-jump" href="#invite">Invite</a>}
        <span className="muted small members-since">League since {new Date(league.createdAt).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}</span>
      </div>
      {error && <p className="bad">{error}</p>}

      <div className={isOwner ? "members-layout" : undefined}>
        <ul className="member-list">
          {league.members.map((m) => {
            const canAvatar = m.you || (isOwner && m.placeholder);
            const claim = m.placeholder ? claimFor(m) : null;
            return (
              <li key={m.id} className={m.you ? "you" : undefined}>
                <PlayerAvatar name={m.displayName} passcode={m.avatar} size="md" />
                <div className="member-text">
                  <div className="member-name">
                    <strong>{m.displayName}</strong>
                    {m.you && <span className="badge">you</span>}
                  </div>
                  <div className="member-sub">
                    <span className={m.role === "OWNER" ? "owner" : undefined}>{roleLabel[m.role]}</span>
                    {m.placeholder && <span> · no account yet{claim ? " · claim link out" : ""}</span>}
                    {canAvatar && (
                      <> · <button className="link" onClick={() => setPicking(m)}>{m.avatar ? "change avatar" : "choose avatar"}</button></>
                    )}
                  </div>
                </div>
                {isOwner && (
                  <div className="member-actions">
                    {m.placeholder && (
                      <button className="sm" onClick={() => void copyClaimLink(m)} title="A link that hands this seat to whoever opens it">
                        {claim ? "Copy claim link" : "Claim link"}
                      </button>
                    )}
                    {m.role !== "OWNER" && <button className="ghost sm danger-text" onClick={() => void remove(m)}>Remove</button>}
                  </div>
                )}
              </li>
            );
          })}
        </ul>

        {isOwner && (
          <aside className="panel invite-panel" id="invite">
            <h4>Invite people <InfoTip>
              Anyone with the link can join while it's valid. To hand a placeholder's seat to someone, use the claim link on their row instead.
            </InfoTip></h4>
            <CreateInvite leagueId={league.id} onCreated={invites.reload} />
            {invites.error && <p className="bad">Couldn't load invites ({invites.error})</p>}
            {invites.data && (
              <InviteList
                invites={invites.data}
                onRevoke={(code) => void act(() => apiDelete(`/leagues/${league.id}/invites/${code}`), { invites: true })}
              />
            )}
            <AddPlaceholder leagueId={league.id} onAdded={reload} />
          </aside>
        )}
      </div>

      {picking && (
        <AvatarPicker name={picking.displayName} current={picking.avatar} canRemove={!!picking.ownAvatar} onClose={() => setPicking(null)}
                      scopes={[{ scope: "default", label: "Every season" }]}
                      note={`For ${league.name} only${picking.you ? "; elsewhere your profile avatar shows" : ""}.`}
                      onSave={async (choice) => {
                        await apiPut(`/leagues/${league.id}/members/${picking.id}/avatar`, choice ?? { cardId: null });
                        reload();
                      }} />
      )}
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
    <div className="invite-block">
      <h5>Add a placeholder <InfoTip>
        A named seat for someone without an account yet, so you can set up seasons now. Send them its claim link later.
      </InfoTip></h5>
      <form onSubmit={submit} className="stack-form">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name" maxLength={40} required aria-label="Placeholder name" />
        <button type="submit" disabled={!name.trim()}>Add</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </div>
  );
}

function CreateInvite({ leagueId, onCreated }: { leagueId: string; onCreated: () => void }) {
  const [days, setDays] = useState("7");
  const [maxUses, setMaxUses] = useState("");
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const created = await apiPost<InviteView>(`/leagues/${leagueId}/invites`, {
        expiresInDays: days === "never" ? null : Number(days),
        maxUses: maxUses.trim() === "" ? null : Number(maxUses),
      });
      setMaxUses("");
      onCreated();
      await copyText(inviteLink(created.code));
      toast("Invite link created and copied.");
    } catch (err) {
      setError((err as Error).message);
    }
  };
  return (
    <>
      <form onSubmit={submit} className="stack-form">
        <select value={days} onChange={(e) => setDays(e.target.value)} aria-label="Expires">
          <option value="1">Expires in 1 day</option>
          <option value="7">Expires in 7 days</option>
          <option value="30">Expires in 30 days</option>
          <option value="never">Never expires</option>
        </select>
        <input
          value={maxUses}
          onChange={(e) => setMaxUses(e.target.value.replace(/\D/g, ""))}
          placeholder="Max uses (blank = any)"
          inputMode="numeric"
          aria-label="Max uses"
        />
        <button type="submit" className="primary">Create link</button>
      </form>
      {error && <p className="bad">{error}</p>}
    </>
  );
}
