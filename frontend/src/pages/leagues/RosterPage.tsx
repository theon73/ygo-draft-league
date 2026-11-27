import { Link, useParams } from "react-router-dom";
import PoolGrid from "./PoolGrid";
import { countsLine, GROUPS, playable, RosterNav, useRosters } from "./RostersPage";

// One player's roster with card art, grouped by deck. Under each card: how many copies they may
// play (the league's copy limit, or the ban list's), and the pick or claim it came from.
export default function RosterPage() {
  const { memberId = "" } = useParams();
  const rosters = useRosters();

  if (rosters.error) return <p className="bad">Couldn't load the rosters ({rosters.error})</p>;
  if (!rosters.data) return <p className="muted">Loading…</p>;
  const roster = rosters.data.players.find((p) => p.memberId === memberId);

  return (
    <>
      <h4>{roster ? `${roster.displayName}'s roster` : "Roster"}</h4>
      <RosterNav rosters={rosters.data} current={roster?.memberId} />
      {!roster ? (
        <p>That player isn't in this season. <Link to="..">All rosters</Link></p>
      ) : (
        <>
          <p>
            {roster.teamName && <><strong>{roster.teamName}</strong> · </>}
            <span className="muted">{countsLine(roster)}</span>
          </p>
          {roster.cards.length === 0 && <p className="muted">No cards yet.</p>}
          {GROUPS.map(({ group, label }) => {
            const entries = roster.cards.filter((c) => c.group === group);
            if (entries.length === 0) return null;
            const byCard = new Map(entries.map((e) => [e.card.cardId, e]));
            return (
              <section key={group}>
                <h5>{label} <span className="muted">({entries.length})</span></h5>
                <PoolGrid
                  cards={entries.map((e) => e.card)}
                  hideOwner
                  action={(c) => {
                    const n = playable(c);
                    const entry = byCard.get(c.cardId);
                    return (
                      <span className="muted">
                        {n === 0 ? "Can't be played" : `Play up to ${n}`}
                        {entry?.source === "DRAFT" && entry.draftPick != null && ` · pick ${entry.draftPick}`}
                        {entry?.source === "FREE_AGENCY" && ` · claimed ${entry.acquiredWeek === 0 ? "before week 1" : `week ${entry.acquiredWeek}`}`}
                      </span>
                    );
                  }}
                />
              </section>
            );
          })}
        </>
      )}
    </>
  );
}
