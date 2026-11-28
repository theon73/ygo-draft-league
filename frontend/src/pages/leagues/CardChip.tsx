import { Link } from "react-router-dom";
import type { CardRef } from "../../api/client";
import CardImage from "../../components/CardImage";
import CardPreview from "../../components/CardPreview";
import { useLeague } from "./LeagueLayout";
import { seasonCardPath } from "./RostersPage";
import { useSeason } from "./SeasonLayout";

/**
 * A card named with its art (YGO-34): a small thumbnail and the name, linking to the card's season
 * page, with the hover / tap preview. For feeds and lists where a card is mentioned.
 */
export default function CardChip({ card }: { card: CardRef }) {
  const { league } = useLeague();
  const { season } = useSeason();
  return (
    <CardPreview cardId={card.cardId}>
      <span className="card-chip">
        <CardImage passcode={card.passcode} size="small" alt="" className="chip-art" />
        <Link to={seasonCardPath(league.id, season.id, card.cardId)}>{card.name}</Link>
      </span>
    </CardPreview>
  );
}
