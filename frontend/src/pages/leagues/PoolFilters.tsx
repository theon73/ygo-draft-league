import { useState } from "react";
import type { PoolFacets } from "../../api/client";
import ChipGroup from "../../components/ChipGroup";
import MultiSelect from "../../components/MultiSelect";
import { activeFilterCount, type MultiParam, type RangeParam } from "./poolQuery";

// Search, sort and the browse filters. Within a filter, choices mean "any of these"; different
// filters must all match. So "Fusion, Synchro" + "Effect" = effect Fusions and effect Synchros.

const opts = (pairs: [string, string][]) => pairs.map(([value, label]) => ({ value, label }));
const KINDS = opts([["MONSTER", "Monsters"], ["SPELL", "Spells"], ["TRAP", "Traps"]]);
const DECKS = opts([["MAIN", "Main Deck"], ["EXTRA", "Extra Deck"]]);
const SUMMONS = opts([["RITUAL", "Ritual"], ["FUSION", "Fusion"], ["SYNCHRO", "Synchro"], ["XYZ", "Xyz"], ["LINK", "Link"]]);
const TEXTS = opts([["NORMAL", "Normal (no effect)"], ["EFFECT", "Effect"]]);
const ABILITIES = opts([["TUNER", "Tuner"], ["FLIP", "Flip"], ["UNION", "Union"], ["GEMINI", "Gemini"], ["SPIRIT", "Spirit"], ["TOON", "Toon"], ["PENDULUM", "Pendulum"]]);
const BAN = opts([["FORBIDDEN", "Forbidden"], ["LIMITED", "Limited"], ["SEMI_LIMITED", "Semi-Limited"], ["UNLIMITED", "Not on the list"]]);
const SORTS = opts([["", "Name"], ["RELEASE", "Newest first"], ["ATK", "ATK, high to low"], ["DEF", "DEF, high to low"], ["LEVEL", "Level/Rank/Link, high to low"]]);
const RANGES: { name: RangeParam; label: string }[] = [
  { name: "level", label: "Level" }, { name: "rank", label: "Rank" }, { name: "link", label: "Link rating" },
  { name: "atk", label: "ATK" }, { name: "def", label: "DEF" }, { name: "scale", label: "Pendulum scale" },
];
const plain = (values: string[]) => values.map((v) => ({ value: v, label: v }));

export default function PoolFilters({ params, setOne, setList, clear, facets, hasBanList }: {
  params: URLSearchParams;
  setOne: (name: string, value: string) => void;
  setList: (name: MultiParam, values: string[]) => void;
  clear: () => void;
  facets: PoolFacets | null;
  hasBanList: boolean;
}) {
  const [q, setQ] = useState(params.get("q") ?? "");
  const [cardText, setCardText] = useState(params.get("cardText") ?? "");
  const kinds = params.getAll("kind");
  const showsKind = (k: string) => kinds.length === 0 || kinds.includes(k);
  const active = activeFilterCount(params);
  const list = (name: MultiParam) => params.getAll(name);

  return (
    <div className="pool-filters">
      <form onSubmit={(e) => { e.preventDefault(); setOne("q", q.trim()); setOne("cardText", cardText.trim()); }}>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name contains" aria-label="Name contains" />
        <input value={cardText} onChange={(e) => setCardText(e.target.value)} placeholder="Card text contains (e.g. banish)"
               aria-label="Card text contains" maxLength={100} />
        <button type="submit">Search</button>
        <select value={params.get("sort") ?? ""} onChange={(e) => setOne("sort", e.target.value)} aria-label="Sort by">
          {SORTS.map((o) => <option key={o.value} value={o.value}>Sort: {o.label}</option>)}
        </select>
      </form>
      <ChipGroup label="Show" options={KINDS} selected={kinds} onChange={(v) => setList("kind", v)} />
      <details>
        <summary>More filters{active > 0 && ` (${active} on)`}</summary>
        <div className="filter-sections">
          {showsKind("MONSTER") && (
            <section>
              <h5>Monsters</h5>
              <ChipGroup label="Deck" options={DECKS} selected={list("deck")} onChange={(v) => setList("deck", v)} />
              <ChipGroup label="Summon" options={SUMMONS} selected={list("summon")} onChange={(v) => setList("summon", v)} />
              <ChipGroup label="Card" options={TEXTS} selected={list("text")} onChange={(v) => setList("text", v)} />
              <ChipGroup label="Ability" options={ABILITIES} selected={list("ability")} onChange={(v) => setList("ability", v)} />
              <ChipGroup label="Attribute" options={plain(facets?.attributes ?? [])} selected={list("attribute")} onChange={(v) => setList("attribute", v)} />
              <MultiSelect label="Type" options={facets?.monsterTypes ?? []} selected={list("monsterType")} onChange={(v) => setList("monsterType", v)} />
              <div className="ranges">
                {RANGES.map((r) => (
                  <Range key={r.name} label={r.label} min={params.get(`${r.name}Min`) ?? ""} max={params.get(`${r.name}Max`) ?? ""}
                         onChange={(end, value) => setOne(`${r.name}${end}`, value)} />
                ))}
              </div>
            </section>
          )}
          {showsKind("SPELL") && (facets?.spellTypes.length ?? 0) > 0 && (
            <section>
              <h5>Spells</h5>
              <ChipGroup label="Type" options={plain(facets!.spellTypes)} selected={list("spellType")} onChange={(v) => setList("spellType", v)} />
            </section>
          )}
          {showsKind("TRAP") && (facets?.trapTypes.length ?? 0) > 0 && (
            <section>
              <h5>Traps</h5>
              <ChipGroup label="Type" options={plain(facets!.trapTypes)} selected={list("trapType")} onChange={(v) => setList("trapType", v)} />
            </section>
          )}
          <section>
            <h5>Any card</h5>
            <MultiSelect label="Archetype" options={facets?.archetypes ?? []} selected={list("archetype")} onChange={(v) => setList("archetype", v)} />
            {hasBanList && <ChipGroup label="Ban list" options={BAN} selected={list("banStatus")} onChange={(v) => setList("banStatus", v)} />}
          </section>
          {(active > 0 || kinds.length > 0) && <p><button type="button" onClick={() => { setQ(""); setCardText(""); clear(); }}>Clear all filters</button></p>}
        </div>
      </details>
    </div>
  );
}

// A min–max pair that applies on Enter or when the box loses focus, not on every keystroke.
function Range({ label, min, max, onChange }: { label: string; min: string; max: string; onChange: (end: "Min" | "Max", value: string) => void }) {
  return (
    <span className="range">
      {/* Keyed on the URL value, so clearing filters (or Back) resets what the box shows. */}
      {label} <NumberBox key={`min-${min}`} value={min} placeholder="min" label={`${label} minimum`} onCommit={(v) => onChange("Min", v)} />
      –<NumberBox key={`max-${max}`} value={max} placeholder="max" label={`${label} maximum`} onCommit={(v) => onChange("Max", v)} />
    </span>
  );
}

function NumberBox({ value, placeholder, label, onCommit }: { value: string; placeholder: string; label: string; onCommit: (v: string) => void }) {
  const [draft, setDraft] = useState(value);
  const commit = () => { if (draft.trim() !== value) onCommit(draft.trim()); };
  return (
    <input type="number" className="narrow-num" placeholder={placeholder} aria-label={label} value={draft}
           onChange={(e) => setDraft(e.target.value)} onBlur={commit}
           onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); commit(); } }} />
  );
}
