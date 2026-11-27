import { Link } from "react-router-dom";
import type { NeedsYouItem } from "../api/client";

// "Needs you" (YGO-31): what's waiting on the viewer, as a list of banners, each with its action.
// On Home it spans every league (so each says which league and season); on a season's Overview
// it's that season's only.

const ACTION: Record<NeedsYouItem["kind"], string> = {
  PICK: "Pick now", REPORT: "Report", CONFIRM: "Review", TRADE_OFFER: "Open trades", DISPUTE: "Settle",
  TRADE_APPROVAL: "Review", START_DRAFT: "Go to the draft", FINISH_SETUP: "Continue setup", START_PLAYOFFS: "Start them",
  COMPLETE_SEASON: "Complete it",
};

export default function NeedsYou({ items, showWhere = false }: { items: NeedsYouItem[]; showWhere?: boolean }) {
  if (items.length === 0) return null;
  return (
    <ul className="needs-you" aria-label="Needs you">
      {items.map((i, n) => (
        <li key={`${i.seasonId}-${i.kind}-${i.matchId ?? n}`} className={`needs-${i.kind.toLowerCase()}${i.commissioner ? " commish" : ""}`}>
          <span className="needs-dot" aria-hidden="true" />
          <span className="needs-text">
            <strong>{i.title}</strong>
            <span className="needs-detail">
              {i.detail}
              {i.due && <> · {i.kind === "START_DRAFT" ? "planned for" : "due"} {when(i.due)}</>}
              {showWhere && <> · {i.league}, {i.season}</>}
            </span>
          </span>
          {i.commissioner && <span className="badge neutral">commissioner</span>}
          <Link className={`button sm${i.kind === "PICK" || !i.commissioner ? " primary" : ""}`}
                to={`/leagues/${i.leagueId}/seasons/${i.seasonId}/${i.tab}`}>
            {ACTION[i.kind]}
          </Link>
        </li>
      ))}
    </ul>
  );
}

function when(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}
