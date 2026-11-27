import { useState } from "react";
import { apiPatch, apiPost, apiPut } from "../../api/client";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import { useConfirm } from "../../components/Feedback";

// A member's roles in this season. Commissioner and player are independent, so a person can be
// both; "none" means they're in the league and just follow the season.
type SeasonRole = "none" | "player" | "commissioner" | "both";

const ROLE_LABEL: Record<SeasonRole, string> = {
  none: "—",
  player: "Player",
  commissioner: "Commissioner",
  both: "Commissioner & player",
};

const roleOf = (commissioner: boolean, player: boolean): SeasonRole =>
  commissioner && player ? "both" : commissioner ? "commissioner" : player ? "player" : "none";

interface Row {
  memberId: string;
  displayName: string;
  placeholder: boolean;
  owner: boolean;
  you: boolean;
  commissioner: boolean;
  seat: number | null; // null if not playing
  teamName: string | null;
}

export default function SeasonSetup() {
  const { league } = useLeague();
  const { season, reload } = useSeason();
  const [error, setError] = useState<string | null>(null);
  const confirm = useConfirm();
  const base = `/leagues/${league.id}/seasons/${season.id}`;
  const manage = season.canManage;

  const act = async (change: () => Promise<unknown>) => {
    setError(null);
    try {
      await change();
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  // One row per member: players in seat order, then commissioners who don't play, then (for
  // commissioners, who can assign roles) everyone else in the league, by name. Everyone else sees
  // only people with a role, plus always their own row, so nobody wonders where they went.
  const players = season.participants;
  const commissionerIds = new Set(season.commissioners.map((c) => c.memberId));
  const ownerId = season.commissioners.find((c) => c.owner)?.memberId;
  const memberInfo = new Map(league.members.map((m) => [m.id, m]));
  const row = (memberId: string, displayName: string, placeholder: boolean): Row => {
    const p = players.find((x) => x.memberId === memberId);
    return {
      memberId, displayName, placeholder,
      owner: memberId === ownerId,
      you: memberInfo.get(memberId)?.you ?? false,
      commissioner: commissionerIds.has(memberId),
      seat: p?.seat ?? null,
      teamName: p?.teamName ?? null,
    };
  };
  const rows: Row[] = [
    ...players.map((p) => row(p.memberId, p.displayName, p.placeholder)),
    ...season.commissioners.filter((c) => !players.some((p) => p.memberId === c.memberId))
      .map((c) => row(c.memberId, c.displayName, false)),
    ...(manage
      ? season.candidates
          .filter((c) => !players.some((p) => p.memberId === c.memberId) && !commissionerIds.has(c.memberId))
          .map((c) => row(c.memberId, c.displayName, c.placeholder))
      : []),
  ];
  const me = league.members.find((m) => m.you);
  if (!manage && me && !rows.some((r) => r.memberId === me.id)) rows.push(row(me.id, me.displayName, me.placeholder));
  const following = !!me && !season.youPlay && !commissionerIds.has(me.id) && me.id !== ownerId;

  const setRole = (r: Row, role: SeasonRole) =>
    act(() => apiPut(`${base}/members/${r.memberId}`, {
      commissioner: role === "commissioner" || role === "both",
      player: role === "player" || role === "both",
    }));

  const move = (seatIndex: number, delta: number) => {
    const order = players.map((p) => p.memberId);
    const [moved] = order.splice(seatIndex, 1);
    order.splice(seatIndex + delta, 0, moved);
    void act(() => apiPut(`${base}/seats`, { memberIds: order }));
  };

  const randomize = async () => {
    const again = season.lastDraw ? " This replaces the current order." : "";
    if (!(await confirm({
      title: "Draw the seat order at random?",
      message: `A random order for ${players.length} players.${again} Every draw is recorded and visible to the league.`,
      confirmLabel: "Draw seats",
    }))) return;
    void act(() => apiPost(`${base}/seats/randomize`));
  };

  // The owner always commissions; placeholders can't (no account). Once the season has started,
  // who plays is fixed, so only options that keep the player status are offered.
  const optionsFor = (r: Row): SeasonRole[] => {
    let options: SeasonRole[] = r.owner ? ["commissioner", "both"] : r.placeholder ? ["none", "player"] : ["none", "player", "commissioner", "both"];
    if (!season.editable) {
      const plays = r.seat !== null;
      options = options.filter((o) => (o === "player" || o === "both") === plays);
    }
    return options;
  };

  return (
    <>
      {!season.editable && <p className="muted">This season has started, so who plays and the seat order are fixed. Commissioners can still change.</p>}
      {error && <p className="bad">{error}</p>}

      <h4>Members</h4>
      <p className="muted">
        {manage
          ? "Give each league member their role this season. Commissioners run the season (you don't have to play to be one); the league owner always is one. Anyone left at — still follows the season."
          : "Who's running and playing this season. Everyone else in the league can follow along."}
      </p>
      {following && (
        <p className="muted">
          You're not playing this season; you can follow along. Ask {season.commissioners.length === 1 ? "the commissioner" : "a commissioner"} if
          you should be in it.
        </p>
      )}
      {rows.length === 0 ? (
        <p className="muted">No one has a role in this season yet.</p>
      ) : (
        <div className="table-scroll">
        <table>
          <thead>
            <tr><th>Seat</th><th>Member</th><th>Role</th><th>Team name</th>{manage && season.editable && <th />}</tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const seatIndex = r.seat === null ? -1 : r.seat - 1;
              const current = roleOf(r.commissioner || r.owner, r.seat !== null);
              return (
                <tr key={r.memberId} className={current === "none" ? "dim" : undefined}>
                  <td>{r.seat ?? ""}</td>
                  <td>
                    {r.displayName}
                    {r.you && <span className="badge">you</span>}
                    {r.owner && <span className="muted"> · league owner</span>}
                    {r.placeholder && <span className="muted"> · no account yet</span>}
                  </td>
                  <td>
                    {manage ? (
                      <select value={current} onChange={(e) => void setRole(r, e.target.value as SeasonRole)}
                              aria-label={`Role of ${r.displayName} this season`}>
                        {optionsFor(r).map((o) => <option key={o} value={o}>{ROLE_LABEL[o]}</option>)}
                      </select>
                    ) : (
                      ROLE_LABEL[current]
                    )}
                  </td>
                  <td>
                    {r.seat !== null && (
                      <TeamName name={r.teamName} canEdit={manage || r.you}
                                onSave={(teamName) => act(() => apiPatch(`${base}/participants/${r.memberId}`, { teamName }))} />
                    )}
                  </td>
                  {manage && season.editable && (
                    <td className="actions">
                      {r.seat !== null && (
                        <>
                          <button onClick={() => move(seatIndex, -1)} disabled={seatIndex === 0} aria-label={`Move ${r.displayName} up`}>↑</button>
                          <button onClick={() => move(seatIndex, 1)} disabled={seatIndex === players.length - 1} aria-label={`Move ${r.displayName} down`}>↓</button>
                        </>
                      )}
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>
      )}

      {manage && season.editable && (
        <p><button onClick={() => void randomize()} disabled={players.length < 2}>Randomize seats</button></p>
      )}
      {season.lastDraw && (
        <p className="muted">
          Seats last drawn at random by {season.lastDraw.drawnBy} on {new Date(season.lastDraw.drawnAt).toLocaleString()}:{" "}
          {season.lastDraw.order.map((name, i) => `${i + 1}. ${name}`).join(", ")}
        </p>
      )}
      <p className="muted">The calendar, format and ban list are on the Rules tab; the card pool is on the Pool tab.</p>
    </>
  );
}

function TeamName({ name, canEdit, onSave }: { name: string | null; canEdit: boolean; onSave: (name: string) => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name ?? "");
  if (!editing) {
    return (
      <>
        {name ?? <span className="muted">—</span>}
        {canEdit && <button className="link" onClick={() => { setValue(name ?? ""); setEditing(true); }}>edit</button>}
      </>
    );
  }
  return (
    <form className="inline" onSubmit={(e) => { e.preventDefault(); void onSave(value).then(() => setEditing(false)); }}>
      <input value={value} onChange={(e) => setValue(e.target.value)} maxLength={40} placeholder="Team name (blank to clear)" autoFocus />
      <button type="submit" className="primary sm">Save</button>
      <button type="button" className="ghost sm" onClick={() => setEditing(false)}>Cancel</button>
    </form>
  );
}
