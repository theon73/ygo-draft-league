import type { ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import type { CardDetail, OwnershipChange, SeasonCard } from "../../api/client";
import CardImage from "../../components/CardImage";
import { cardStats } from "../../components/cardStats";
import { useApi } from "../../hooks/useApi";
import { statusLabel } from "../explore/banStatus";
import { useLeague } from "./LeagueLayout";
import { playable } from "./RostersPage";
import { useSeason } from "./SeasonLayout";
import { Loading } from "../../components/Feedback";

// A card as this season sees it: its pool flags, who holds it, and every change of hands. The
// card's text comes from the catalog; prints and official ban lists stay on the Explorer page.
export default function SeasonCardPage() {
  const { league } = useLeague();
  const { season, draftVersion } = useSeason();
  const { cardId = "" } = useParams();
  const info = useApi<SeasonCard>(`/leagues/${league.id}/seasons/${season.id}/cards/${encodeURIComponent(cardId)}`, String(draftVersion));
  const detail = useApi<CardDetail>(`/explore/cards/${encodeURIComponent(cardId)}`);

  if (info.status === 404) return <p>That card isn't in this season's pool. <Link to={`/explore/cards/${cardId}`}>See it in the Explorer</Link></p>;
  if (info.error) return <p className="bad">Couldn't load the card ({info.error})</p>;
  if (!info.data) return <Loading />;
  const { card: c, owners, history } = info.data;
  const n = playable(c);
  const rosterLink = (memberId: string | null, name: string | null) =>
    memberId ? <Link to={`/leagues/${league.id}/seasons/${season.id}/rosters/${memberId}`}>{name}</Link> : name;

  return (
    <section>
      <h4>{c.name}</h4>
      <div className="season-card">
        <CardImage passcode={c.passcode} size="full" alt={c.name} />
        <div>
          <p>
            {c.cardType} · {cardStats(c)}{c.archetype && ` · ${c.archetype}`}
            <br />
            <span className="muted">TCG release {c.tcgDate} · <Link to={`/explore/cards/${c.cardId}`}>prints and ban lists</Link></span>
          </p>
          {detail.data && <p className="card-text">{detail.data.description}</p>}

          <h5>This season</h5>
          <ul>
            {c.removedInWeek !== null ? (
              <li>Removed from the pool{c.removedInWeek > 0 ? ` from week ${c.removedInWeek}` : " during setup"}.</li>
            ) : (
              <>
                <li>
                  {c.softBanned ? "Soft-banned: it can't be drafted or played." : n === 0 ? "Can't be played (0 copies)." : `Play up to ${n} in a deck`}
                  {!c.softBanned && (c.maxPlayable !== null ? " (league rule)" : c.banStatus ? ` (${statusLabel[c.banStatus]})` : "")}
                  {!c.softBanned && n > 0 && "."}
                </li>
                {c.requiresOwnership && <li>Must be on your roster to be played.</li>}
                {c.source === "EXCEPTION" && <li>Added as an exception{c.reason ? `: ${c.reason}` : ""}{c.addedInWeek > 0 ? `, from week ${c.addedInWeek}` : ""}.</li>}
              </>
            )}
            <li>
              {owners.length === 0
                ? "Nobody holds it: it's a free agent."
                : <>Held by {owners.map((o, i) => (
                    <span key={o.memberId}>{i > 0 && ", "}{rosterLink(o.memberId, o.displayName)}{o.quantity > 1 && ` (${o.quantity})`}</span>
                  ))}.</>}
            </li>
          </ul>

          <h5>Ownership history</h5>
          {history.length === 0 ? (
            <p className="muted">Nobody has held it this season.</p>
          ) : (
            <ul>
              {history.map((h, i) => (
                <li key={i}>
                  {describe(h, rosterLink)}
                  <span className="muted"> · {h.week === 0 ? "draft" : `week ${h.week}`}, {new Date(h.at).toLocaleString()}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}

function describe(h: OwnershipChange, who: (memberId: string | null, name: string | null) => ReactNode) {
  const by = h.onBehalfBy ? ` (by ${h.onBehalfBy})` : "";
  if (h.source === "TRADE") {
    return <>{who(h.toMemberId, h.to)} got it from {who(h.fromMemberId, h.from)} in a trade{by}</>;
  }
  if (h.source === "FREE_AGENCY") {
    return h.to !== null
      ? <>{who(h.toMemberId, h.to)} claimed it from free agency{by}</>
      : <>{who(h.fromMemberId, h.from)} dropped it to free agency{by}</>;
  }
  return (
    <>
      {who(h.toMemberId, h.to)} drafted it{h.draftPick != null && ` with pick ${h.draftPick}`}
      {h.auto ? " (auto-pick from the queue)" : h.onBehalfBy ? ` (picked by ${h.onBehalfBy})` : ""}
    </>
  );
}
