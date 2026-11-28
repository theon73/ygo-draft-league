import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { apiPut, type Roster } from "../../api/client";
import AvatarPicker, { type AvatarChoice, type AvatarScope } from "../../components/AvatarPicker";
import PlayerAvatar from "../../components/PlayerAvatar";
import { useLeague } from "./LeagueLayout";
import { useSeason } from "./SeasonLayout";
import PoolGrid from "./PoolGrid";
import { countsLine, GROUPS, playable, RosterNav, useRosters } from "./RostersPage";
import { Loading } from "../../components/Feedback";

// One player's roster with card art, grouped by deck. Under each card: how many copies they may
// play (the league's copy limit, or the ban list's), and the pick or claim it came from.
export default function RosterPage() {
  const { memberId = "" } = useParams();
  const rosters = useRosters();

  if (rosters.error) return <p className="bad">Couldn't load the rosters ({rosters.error})</p>;
  if (!rosters.data) return <Loading />;
  const roster = rosters.data.players.find((p) => p.memberId === memberId);

  return (
    <>
      {roster ? <RosterHeader roster={roster} onChanged={rosters.reload} /> : <h4>Roster</h4>}
      <RosterNav rosters={rosters.data} current={roster?.memberId} />
      {!roster ? (
        <p>That player isn't in this season. <Link to="..">All rosters</Link></p>
      ) : (
        <>
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
                        {entry?.source === "TRADE" && ` · traded ${entry.acquiredWeek === 0 ? "before week 1" : `week ${entry.acquiredWeek}`}`}
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

// The player's avatar, name, team and counts, with "Change avatar" for whoever may set it: the player
// (for this season or as their default), a commissioner for a placeholder this season, or the
// league owner for a placeholder's default (YGO-30).
function RosterHeader({ roster, onChanged }: { roster: Roster; onChanged: () => void }) {
  const { league, reload: reloadLeague } = useLeague();
  const { season, reload: reloadSeason } = useSeason();
  const [picking, setPicking] = useState(false);
  const part = season.participants.find((p) => p.memberId === roster.memberId);
  // Roughly when trading is open (the Messages page knows about the deadline and says so).
  const tradingOpen = season.status === "ACTIVE" && !season.playoffsStarted;
  const scopes: { scope: AvatarScope; label: string }[] = [];
  if (roster.you || (league.myRole === "OWNER" && roster.placeholder)) {
    scopes.push({ scope: "default", label: `Every ${league.name} season` });
  }
  if (roster.you || (season.canManage && roster.placeholder)) scopes.push({ scope: "season", label: `${season.name} only` });

  const save = async (choice: AvatarChoice | null, scope: AvatarScope) => {
    const path = scope === "season"
      ? `/leagues/${league.id}/seasons/${season.id}/participants/${roster.memberId}/avatar`
      : `/leagues/${league.id}/members/${roster.memberId}/avatar`;
    await apiPut(path, choice ?? { cardId: null });
    onChanged();
    reloadSeason();
    reloadLeague();
  };

  return (
    <div className="roster-header">
      <PlayerAvatar name={roster.displayName} passcode={roster.avatar} size="lg" />
      <div className="roster-header-text">
        <h4>{roster.displayName}{roster.you && <span className="badge">you</span>}</h4>
        <p className="muted">
          {roster.teamName && <><strong className="team-name">{roster.teamName}</strong> · </>}
          {countsLine(roster)}
        </p>
      </div>
      {/* Trades are made in Messages (YGO-35); this is the way in from the cards you want. */}
      {season.youPlay && !roster.you && (
        <Link className="button sm" to={`/leagues/${league.id}/seasons/${season.id}/messages?with=${roster.memberId}${tradingOpen ? "&propose=1" : ""}`}>
          {tradingOpen ? "Propose a trade" : `Message ${roster.displayName}`}
        </Link>
      )}
      {scopes.length > 0 && <button className="sm" onClick={() => setPicking(true)}>{roster.avatar ? "Change avatar" : "Choose an avatar"}</button>}
      {picking && (
        <AvatarPicker name={roster.displayName} current={roster.avatar} scopes={scopes}
                      canRemove={!!part?.ownAvatar || !!league.members.find((m) => m.id === roster.memberId)?.ownAvatar}
                      initialScope={part?.ownAvatar || !scopes.some((s) => s.scope === "default") ? "season" : "default"}
                      onSave={save} onClose={() => setPicking(false)} />
      )}
    </div>
  );
}
