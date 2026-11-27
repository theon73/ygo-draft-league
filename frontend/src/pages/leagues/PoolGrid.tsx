import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type { PoolCard } from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { cardStats } from "../../components/cardStats";
import { statusLabel } from "../explore/banStatus";
import { useLeague } from "./LeagueLayout";
import { seasonCardPath } from "./RostersPage";
import { useSeason } from "./SeasonLayout";

// The browsing view: a grid of card tiles, for planning picks. Hover (or tap) a tile for the
// full card. Curation controls stay in the table view. `action` adds a control under each tile
// (the draft room's "Draft" button); it's a render prop, a function that returns the element.
// The name links to the card's season page (owners and history). hideOwner: on a roster, or in the
// free agents (where nobody owns anything), the owner badge says nothing.
export default function PoolGrid({ cards, action, hideOwner = false }: { cards: PoolCard[]; action?: (c: PoolCard) => ReactNode; hideOwner?: boolean }) {
  const { league } = useLeague();
  const { season } = useSeason();
  return (
    <ul className="pool-grid">
      {cards.map((c) => (
        <li key={c.cardId} className={c.softBanned || c.effectiveMax === 0 ? "unusable" : undefined}>
          <CardPreview cardId={c.cardId}>
            <CardImage passcode={c.passcode} size="small" alt="" className="tile-art" />
          </CardPreview>
          <div className="tile-name" title={c.name}><Link to={seasonCardPath(league.id, season.id, c.cardId)}>{c.name}</Link></div>
          <div className="tile-stats">{cardStats(c)}</div>
          <div className="tile-badges">
            {c.banStatus && <span className={`badge ban-${c.banStatus.toLowerCase()}`}>{statusLabel[c.banStatus]}</span>}
            {c.maxPlayable !== null && <span className="badge">max {c.maxPlayable}</span>}
            {c.softBanned && <span className="badge ban-forbidden">soft-banned</span>}
            {c.requiresOwnership && <span className="badge">must own</span>}
            {c.source === "EXCEPTION" && <span className="badge" title={c.reason ?? undefined}>exception</span>}
            {c.ownedBy && !hideOwner && <span className="badge owned" title="Drafted by">{c.ownedBy}</span>}
          </div>
          {action && <div className="tile-action">{action(c)}</div>}
        </li>
      ))}
    </ul>
  );
}
