// The pool list's filters live in the URL (?kind=SPELL&kind=TRAP&summon=FUSION…), so Back, reload
// and shared links keep them. Multi-select filters repeat the parameter. These are the names the
// backend's PoolQuery binds.

export const MULTI_PARAMS = [
  "kind", "deck", "summon", "text", "ability", "attribute", "monsterType", "spellType", "trapType", "archetype", "banStatus",
] as const;
export const RANGE_PARAMS = ["level", "rank", "link", "atk", "def", "scale"] as const;
export const SINGLE_PARAMS = ["show", "q", "cardText", "sort"] as const;

export type MultiParam = (typeof MULTI_PARAMS)[number];
export type RangeParam = (typeof RANGE_PARAMS)[number];

/** Filters that only apply to monsters; choosing spells/traps only clears them. */
export const MONSTER_ONLY: string[] = [
  "deck", "summon", "text", "ability", "attribute", "monsterType",
  ...RANGE_PARAMS.flatMap((r) => [`${r}Min`, `${r}Max`]),
];

/** How many browse filters are set (search, sort and the curation tabs don't count). */
export function activeFilterCount(params: URLSearchParams): number {
  const multi = MULTI_PARAMS.filter((p) => p !== "kind" && params.getAll(p).length > 0).length;
  const ranges = RANGE_PARAMS.filter((r) => params.get(`${r}Min`) || params.get(`${r}Max`)).length;
  return multi + ranges + (params.get("cardText") ? 1 : 0);
}

export function poolQueryString(params: URLSearchParams, page: number, pageSize: number): string {
  const out = new URLSearchParams();
  for (const name of SINGLE_PARAMS) {
    const v = params.get(name);
    if (v) out.set(name, v);
  }
  for (const name of MULTI_PARAMS) for (const v of params.getAll(name)) out.append(name, v);
  for (const r of RANGE_PARAMS) {
    for (const end of ["Min", "Max"]) {
      const v = params.get(r + end);
      if (v) out.set(r + end, v);
    }
  }
  out.set("page", String(page));
  out.set("pageSize", String(pageSize));
  return out.toString();
}
