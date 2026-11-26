// A one-line summary of a card's stats, e.g. "LIGHT · Spellcaster · Lv 7 · 2500/2100" for a
// monster or "Quick-Play Spell" for a spell. Works with any object carrying the catalog fields.

export interface CardStatFields {
  cardType: string; // e.g. "Effect Monster", "Spell Card", "XYZ Monster"
  race: string; // monster type, or the spell/trap subtype
  attribute: string | null;
  level: number | null;
  rank: number | null;
  linkRating: number | null;
  atk: number | null;
  def: number | null;
}

export function cardStats(c: CardStatFields): string {
  if (c.cardType === "Spell Card" || c.cardType === "Trap Card") {
    return `${c.race} ${c.cardType === "Spell Card" ? "Spell" : "Trap"}`;   // "Normal Spell", "Counter Trap"
  }
  if (c.cardType === "Token") return "Token";
  const level = c.linkRating != null ? `Link ${c.linkRating}` : c.rank != null ? `Rank ${c.rank}` : c.level != null ? `Lv ${c.level}` : null;
  const stats = c.linkRating != null ? `${c.atk ?? "?"}` : `${c.atk ?? "?"}/${c.def ?? "?"}`;   // "?" ATK/DEF are stored as null
  return [c.attribute, c.race, level, stats].filter(Boolean).join(" · ");
}
