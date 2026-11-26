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

// Spring Security's SPA CSRF mode: the backend sets a readable XSRF-TOKEN cookie, and every
// state-changing request must echo it in the X-XSRF-TOKEN header.
function csrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
}

export async function apiPost<T = void>(path: string, body?: unknown): Promise<T> {
  return apiSend<T>("POST", path, body);
}

export async function apiPut<T = void>(path: string, body?: unknown): Promise<T> {
  return apiSend<T>("PUT", path, body);
}

export async function apiPatch<T = void>(path: string, body?: unknown): Promise<T> {
  return apiSend<T>("PATCH", path, body);
}

export async function apiDelete(path: string, body?: unknown): Promise<void> {
  return apiSend<void>("DELETE", path, body);
}

async function apiSend<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = csrfToken();
  const res = await fetch(`/api${path}`, {
    method,
    credentials: "include",
    headers: {
      ...(body !== undefined && { "Content-Type": "application/json" }),
      ...(token && { "X-XSRF-TOKEN": decodeURIComponent(token) }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) {
    // Our endpoints return {"message": "..."} for validation errors; show that when present.
    let message = `${res.status} ${res.statusText}`;
    try {
      message = (JSON.parse(text) as { message?: string }).message ?? message;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, message);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

// ---- Types mirroring backend DTOs ----

export interface SessionUser {
  displayName: string;
  avatarUrl: string | null;
  admin: boolean;
  identity: string; // provider:subject, what goes in APP_ADMINS
}

export interface Session {
  user: SessionUser | null;
  providers: string[];
  devLogin: boolean;
}

export interface DevUser {
  subject: string;
  displayName: string;
  admin: boolean;
}

export interface SyncResult {
  status: string;
  startedAt: string;
  finishedAt: string | null;
  sourceVersion: string | null;
  cardsAdded: number | null;
  cardsUpdated: number | null;
  error: string | null;
}

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

// Leagues

// A league is a group of people: one owner (who manages it) and members. Commissioner and
// player are per-season roles (see SeasonDetail).
export type LeagueRole = "OWNER" | "MEMBER";

export interface LeagueSummary {
  id: string; // public id, used in URLs
  name: string;
  myRole: LeagueRole;
  memberCount: number;
}

export interface LeagueMember {
  id: string; // public id, for commissioner actions
  displayName: string;
  role: LeagueRole;
  placeholder: boolean; // a seat for someone without an account yet
  you: boolean;
}

export interface LeagueDetail {
  id: string;
  name: string;
  myRole: LeagueRole;
  createdAt: string;
  timeZone: string; // IANA zone; weeks, deadlines and draft times use it
  weekStartsOn: Weekday; // each league week starts on this day...
  weekStartsAt: string; // ...at this local time, "HH:mm"
  currentWeek: { start: string; end: string }; // the week containing now (ISO, with offset)
  members: LeagueMember[];
  seasons: SeasonLink[]; // newest first
  currentSeasonId: string | null; // the season the league page opens on
}

export type Weekday = "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";

// Seasons

export type SeasonStatus = "SETUP" | "DRAFTING" | "ACTIVE" | "COMPLETE";

export interface SeasonLink {
  id: string;
  name: string;
  status: SeasonStatus;
}

export interface SeasonSummary extends SeasonLink {
  createdAt: string;
  playerCount: number;
}

export interface Participant {
  memberId: string;
  displayName: string;
  seat: number;
  teamName: string | null;
  placeholder: boolean;
  you: boolean;
}

export interface Candidate {
  memberId: string;
  displayName: string;
  placeholder: boolean; // no account: can play, can't commission
}

export interface SeasonCommissioner {
  memberId: string;
  displayName: string;
  owner: boolean; // the league owner commissions every season
  you: boolean;
}

export interface SeasonDetail {
  id: string;
  name: string;
  status: SeasonStatus;
  createdAt: string;
  canManage: boolean; // caller commissions this season
  youPlay: boolean; // caller is one of its players
  editable: boolean; // participants and seats can still change (setup)
  commissioners: SeasonCommissioner[]; // owner first
  participants: Participant[]; // in seat order
  candidates: Candidate[]; // commissioners only: every league member
  lastDraw: { drawnBy: string; drawnAt: string; order: string[] } | null;
}

// Invites

export type InviteKind = "OPEN" | "CLAIM";
export type InviteStatus = "VALID" | "EXPIRED" | "REVOKED" | "USED_UP";

export interface InviteView {
  code: string;
  kind: InviteKind;
  seatName: string | null; // claim invites: the placeholder being claimed
  maxUses: number | null; // null = unlimited
  useCount: number;
  expiresAt: string | null; // null = never
  createdAt: string;
}

export interface InvitePreview {
  leagueName: string;
  kind: InviteKind;
  seatName: string | null;
  status: InviteStatus;
  alreadyMember: boolean;
}

export function inviteLink(code: string): string {
  return `${window.location.origin}/invite/${code}`;
}

// Season rules (versioned; each save is a new version)

export interface BanListOption {
  listId: number;
  name: string;
  official: boolean;
  effectiveDate: string | null;
  latestRevision: number;
  latestRevisionId: number; // what picking this list pins the season to
}

export interface SeasonRules {
  version: number | null; // null until first saved
  effectiveFromWeek: number;
  canManage: boolean;
  started: boolean; // season left setup: changes need effectiveFromWeek
  calendar: {
    startDate: string | null;
    regularWeeks: number | null; // set explicitly, or derived from the competition format
    weeksDerived: boolean; // true for a round-robin: follows its cycles and the player count
    timeZone: string;
    weekStartsOn: Weekday;
    weekStartsAt: string;
    weekStartLocked: boolean; // copied in when the season began; else follows the league
  };
  format: {
    tcgCutoff: string | null;
    banList: { revisionId: number; listId: number; name: string; revision: number; official: boolean; latestRevision: number; latestRevisionId: number } | null;
  };
  competition: {
    format: "ROUND_ROBIN" | null; // other formats (Swiss, weekly H2H, ladder) later
    roundRobinCycles: number | null; // how many times each pair meets
    players: number;
    rounds: number | null;
    matchesPerPlayer: number | null;
    byes: boolean; // odd player count: one player sits out each round
  };
  weeks: { number: number; start: string; end: string }[];
  currentWeek: number | null; // 0 = pre-season
  minEffectiveWeek: number;
  history: { version: number; effectiveFromWeek: number; by: string | null; at: string }[];
}

// Season card pool

export interface PoolSummary {
  canManage: boolean;
  started: boolean; // mid-season changes need an effective week
  minEffectiveWeek: number;
  rulesCutoff: string | null; // the cutoff in the rules now
  generatedCutoff: string | null; // the cutoff the dated pool was last generated from
  generatedAt: string | null;
  total: number;
  dated: number;
  exceptions: number;
  softBanned: number;
  requiresOwnership: number;
  overridden: number; // cards with a house copy limit instead of the ban list's
  removed: number;
  banList: string | null; // the ban list in the season's rules
  forbidden: number; // pool cards by ban list status
  limited: number;
  semiLimited: number;
}

export interface PoolCard {
  cardId: number;
  name: string;
  cardType: string;
  archetype: string | null;
  tcgDate: string;
  passcode: number;
  source: "DATED" | "EXCEPTION";
  softBanned: boolean;
  requiresOwnership: boolean;
  maxPlayable: number | null; // this league's override of the copy limit; null = follow the ban list
  reason: string | null;
  addedInWeek: number;
  removedInWeek: number | null;
  banStatus: BanStatus | null; // on the season's ban list
  effectiveMax: number; // override ?? ban list limit (0/1/2) ?? 3
  race: string; // monster type, or a spell/trap's subtype ("Quick-Play", "Continuous", ...)
  attribute: string | null;
  level: number | null;
  rank: number | null;
  linkRating: number | null;
  atk: number | null;
  def: number | null;
}

export interface PoolCardPage {
  total: number;
  page: number;
  pageSize: number;
  items: PoolCard[];
}

export interface PoolCandidate {
  cardId: number;
  name: string;
  cardType: string;
  tcgDate: string;
  passcode: number;
  inPool: boolean;
}

export interface PoolChange {
  kind: "GENERATED" | "ADDED" | "REMOVED" | "FLAGS";
  card: string | null;
  effectiveFromWeek: number;
  detail: Record<string, unknown>;
  reason: string | null;
  by: string | null;
  at: string;
}

export interface PoolFacets {
  attributes: string[];
  monsterTypes: string[];
  spellTypes: string[];
  trapTypes: string[];
  archetypes: string[];
}

// Draft

export interface RoundBlock {
  fromRound: number;
  toRound: number;
  picksPerTurn: number;
}

export interface DraftState {
  status: "NOT_STARTED" | "IN_PROGRESS" | "COMPLETE";
  canManage: boolean;
  timeZone: string; // the league's; scheduledStart is shown in it
  order: "SNAKE" | "LINEAR";
  rounds: RoundBlock[];
  scheduledStart: string | null; // instant
  forbiddenDraftable: boolean;
  roundCount: number;
  picksPerPlayer: number;
  totalPicks: number;
  draftableCards: number;
  seats: { seat: number; displayName: string; teamName: string | null }[];
  board: { overallPick: number; round: number; pickInRound: number; seat: number }[];
  readiness: { item: string; ok: boolean; detail: string }[]; // only before the start
  startedAt: string | null;
  startedBy: string | null;
}
