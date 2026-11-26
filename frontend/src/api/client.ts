// Thin fetch wrapper. Swap in TanStack Query hooks once there's more than a couple of endpoints.

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { credentials: "include" });
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  return res.json() as Promise<T>;
}

// ---- Types mirroring backend DTOs ----

export interface Health {
  status: string;
  time: string;
}

// Explorer (temporary read-only views; see ExploreController)

export interface BanListInfo {
  id: number;
  name: string;
  effectiveDate: string;
  revision: number;
  forbidden: number;
  limited: number;
  semiLimited: number;
}

export interface ExploreSummary {
  cards: number;
  sets: number;
  prints: number;
  lastSync: {
    status: string;
    trigger: string;
    startedAt: string;
    finishedAt: string | null;
    sourceVersion: string | null;
    cardsAdded: number | null;
    cardsUpdated: number | null;
    error: string | null;
  } | null;
  banLists: BanListInfo[];
}

export interface CardPage {
  total: number;
  page: number;
  pageSize: number;
  items: { id: number; name: string; cardType: string; tcgDate: string }[];
}

export type BanStatus = "FORBIDDEN" | "LIMITED" | "SEMI_LIMITED";

export interface CardDetail {
  id: number;
  name: string;
  cardType: string;
  race: string;
  attribute: string | null;
  level: number | null;
  rank: number | null;
  linkRating: number | null;
  pendulumScale: number | null;
  atk: number | null;
  def: number | null;
  archetype: string | null;
  description: string;
  tcgDate: string;
  passcodes: number[];
  prints: { setName: string; setDate: string | null; printCode: string; rarity: string }[];
  banStatuses: { banListId: number; banList: string; status: BanStatus }[];
}

export interface BanListDetail {
  info: BanListInfo;
  note: string | null;
  entries: { cardId: number; name: string; cardType: string; status: BanStatus }[];
}
