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
  avatar?: number | null; // profile avatar (a card art passcode, YGO-30)
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
  items: { id: number; name: string; cardType: string; tcgDate: string; passcodes?: number[] }[];
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
  entries: { cardId: number; name: string; cardType: string; status: BanStatus; passcode: number }[];
  previous: BanListInfo | null; // the official list in effect just before this one
  changes: BanChange[]; // cards whose status differs from `previous`
}

/** A status change between two lists; null = unlimited. */
export interface BanChange {
  cardId: number;
  name: string;
  cardType: string;
  passcode: number;
  from: BanStatus | null;
  to: BanStatus | null;
}

// The Cards page (YGO-38): the catalog, or one format's pool (a cutoff and an official ban list).

export interface CatalogCard {
  cardId: number;
  name: string;
  cardType: string;
  archetype: string | null;
  tcgDate: string;
  passcode: number;
  banStatus: BanStatus | null;
  race: string;
  attribute: string | null;
  level: number | null;
  rank: number | null;
  linkRating: number | null;
  atk: number | null;
  def: number | null;
}

export interface CatalogPage {
  total: number;
  page: number;
  pageSize: number;
  items: CatalogCard[];
}

export interface FormatSummary {
  cutoff: string | null;
  banListId: number | null;
  banList: string | null;
  cards: number;
  monsters: number;
  extra: number;
  spells: number;
  traps: number;
  forbidden: number; // listed cards in the format (a list can name later cards)
  limited: number;
  semiLimited: number;
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
  currentSeason?: SeasonLink | null; // the season the league opens on (YGO-31)
  unread?: number; // your unread trade messages and offers in the league's seasons (YGO-34)
}

export interface LeagueMember {
  id: string; // public id, for commissioner actions
  displayName: string;
  role: LeagueRole;
  placeholder: boolean; // a seat for someone without an account yet
  you: boolean;
  avatar?: number | null; // shown in this league: the league's own for them, else their profile's (YGO-30)
  ownAvatar?: boolean; // it's this league's own, not the profile's
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
  unread?: number; // your unread trade messages and offers in it (YGO-34)
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
  avatar?: number | null; // shown this season (their own for it, else their default) (YGO-30)
  ownAvatar?: boolean; // this season has its own avatar, not the default
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
  lastDraw: SeatDrawRecord | null;
  currentWeek?: number | null; // once active: 0 before week 1, past weekCount after the regular season (YGO-31)
  weekCount?: number | null;
  playoffsStarted?: boolean;
  setupStep?: number | null; // the setup wizard's step to resume at (YGO-32); null when not in use
  unreadMessages?: number; // your unread trade messages and offers in this season (YGO-34)
  copiedFrom?: SeasonRef | null; // the season this one was copied from (YGO-39)
  next?: SeasonRef | null; // the league's next season after this one (YGO-39)
}

/** The latest recorded seat order: a random draw, or (YGO-39) the reverse of an earlier season's standings. */
export interface SeatDrawRecord {
  drawnBy: string;
  drawnAt: string;
  order: string[];
  method?: "RANDOM" | "REVERSE_STANDINGS"; // missing before YGO-39 means RANDOM
  basedOn?: string | null; // REVERSE_STANDINGS: the season whose standings were reversed
}

export interface SeasonRef {
  id: string;
  name: string;
  status: SeasonStatus;
}

/** Something waiting on the viewer (YGO-31): from /me/needs-you (every league) or a season's /needs-you. */
export interface NeedsYouItem {
  kind: "PICK" | "REPORT" | "CONFIRM" | "TRADE_OFFER" | "MESSAGE" | "DISPUTE" | "TRADE_APPROVAL" | "START_DRAFT" | "FINISH_SETUP"
    | "START_PLAYOFFS" | "COMPLETE_SEASON";
  leagueId: string;
  league: string;
  seasonId: string;
  season: string;
  title: string;
  detail: string;
  tab: string; // the season tab where it's done
  due: string | null;
  matchId: string | null;
  commissioner: boolean; // a commissioner's job rather than the viewer's own
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
  seatId: string | null; // claim invites: the placeholder's member id
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
  transactions: TransactionRules;
  results: { autoConfirmHours: number | null; confirmHours: number; tiebreakers: Tiebreaker[] };
  playoffs: PlayoffSettings;
  playoffsStarted: boolean; // then the playoff settings are fixed
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
    matchBestOf: number | null; // as set; null = the default
    bestOf: number; // what applies (default 3)
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
  ownedBy: string | null; // who holds it (display names), once the draft has made owners
}

// Rosters (YGO-22): who holds what. Only the draft moves cards so far; free agency and trades join it.

export type OwnershipSource = "DRAFT" | "FREE_AGENCY" | "TRADE";

export type RosterGroup = "MONSTER" | "EXTRA" | "SPELL" | "TRAP"; // main-deck monsters, extra deck, spells, traps

export interface RosterCard {
  card: PoolCard;
  group: RosterGroup;
  quantity: number; // copies held (1 with exclusive ownership)
  acquiredAt: string;
  acquiredWeek: number; // 0 = the draft
  source: OwnershipSource;
  draftPick: number | null;
}

export interface Roster {
  memberId: string;
  displayName: string;
  teamName: string | null;
  seat: number | null;
  placeholder: boolean;
  you: boolean;
  counts: { total: number; monsters: number; extra: number; spells: number; traps: number };
  cards: RosterCard[]; // by group, then name
  avatar?: number | null; // the avatar art's passcode (YGO-30); null/missing: initials
}

export interface Rosters {
  freeAgents: number; // pool cards nobody holds that could be taken
  players: Roster[]; // in seat order
}

export interface OwnershipChange {
  at: string;
  week: number;
  source: OwnershipSource;
  fromMemberId: string | null; // null: from the draft or a free-agent claim
  from: string | null;
  toMemberId: string | null; // null: dropped to free agency
  to: string | null;
  quantity: number;
  draftPick: number | null;
  auto: boolean; // auto-picked from the queue
  onBehalfBy: string | null; // the commissioner who picked for the player
}

export interface SeasonCard {
  card: PoolCard;
  owners: { memberId: string; displayName: string; quantity: number; since: string; week: number }[];
  history: OwnershipChange[]; // oldest first
}

// Free agency (YGO-23): a claim picks up a free agent and drops a card, as one transaction.

export interface Allowance {
  memberId: string;
  displayName: string;
  you: boolean;
  limit: number | null; // claims per week; null = unlimited
  used: number;
  left: number | null;
}

export interface FreeAgency {
  open: boolean;
  closedReason: string | null;
  week: number; // 0 = before week 1
  budgetWeek: number; // the week claims count toward now (week 0 counts as week 1)
  limit: number | null;
  players: Allowance[]; // whom the caller can claim for: themselves, or everyone for a commissioner
}

export interface CardRef {
  cardId: number;
  name: string;
  passcode: number;
}

export interface TransactionRules {
  freeAgencyPerWeek: number | null; // null = unlimited
  tradesPerWeek: number | null; // null = unlimited
  tradeDeadlineWeek: number | null; // last week trades can happen in; null = none
  commissionerApproval: boolean; // accepted trades wait for a commissioner
}

export type AllowanceKind = "FREE_AGENCY" | "TRADE";

// One executed transaction. added/dropped are from `player`'s side: a claim's pick-up and drop, or
// what the trade's proposer received and gave (the counterparty got the reverse).
export interface SeasonTransaction {
  kind: "FREE_AGENCY" | "TRADE";
  week: number;
  at: string;
  memberId: string;
  player: string;
  counterpartyMemberId: string | null;
  counterparty: string | null;
  added: CardRef[];
  dropped: CardRef[];
  allowances: { kind: AllowanceKind; quantity: number; week: number; fromMemberId: string; from: string; toMemberId: string; to: string }[];
  onBehalfBy: string | null; // a commissioner who made it for a player, or approved the trade
}

// Schedule (YGO-25): the regular season's matchups, one round a week.

export interface SchedulePlayer {
  memberId: string;
  name: string;
  teamName: string | null;
  you: boolean;
  avatar?: number | null; // the avatar art's passcode (YGO-30); null/missing: initials
}

export interface ScheduleWeek {
  week: number;
  start: string | null;
  end: string | null;
  bestOf: number; // from the rules in effect that week
  current: boolean; // this week's matchups (week 1 before the season starts)
  matches: ScheduleMatch[]; // b null = a has a bye
}

// A match's live result (YGO-26). Games follow a / b; tied games are left out, so level games is a draw.
export interface MatchResult {
  gamesA: number;
  gamesB: number;
  forfeit: "A" | "B" | "BOTH" | null; // who forfeited (entered by a commissioner)
  status: "PENDING" | "CONFIRMED" | "DISPUTED";
  autoConfirmed: boolean;
  byCommissioner: boolean;
  reportedBy: string | null; // the player who reported it; null when a commissioner entered it
  games: { game: number; replay: string | null; note: string | null }[]; // only games with a link or a note
  disputeNote: string | null;
  reportedAt: string;
}

export interface ScheduleMatch {
  id: string;
  a: SchedulePlayer;
  b: SchedulePlayer | null;
  mine: boolean;
  result: MatchResult | null;
  overdue: boolean; // its week is over without a final result (still reportable)
  canReport: boolean;
  canConfirm: boolean; // also: can dispute
  canEnter: boolean; // commissioners: enter or correct the result
}

export type Tiebreaker = "GAME_DIFF" | "GAMES_WON" | "HEAD_TO_HEAD";

export interface StandingsRow {
  rank: number;
  memberId: string;
  name: string;
  teamName: string | null;
  you: boolean;
  avatar?: number | null; // the avatar art's passcode (YGO-30); null/missing: initials
  played: number;
  wins: number;
  losses: number;
  draws: number;
  gamesWon: number;
  gamesLost: number;
  gameDiff: number;
}

export interface SeasonStandings {
  tiebreakers: Tiebreaker[];
  showDraws: boolean; // a draw happened this season
  remaining: number; // matches without a final result
  waiting: number; // reported results not final yet
  rows: StandingsRow[];
}

export interface Schedule {
  generated: boolean;
  canManage: boolean;
  canGenerate: boolean; // draw it now (or again, before week 1)
  why: string | null; // why it can't be drawn now (commissioners)
  currentWeek: number; // 0 before week 1
  drawnAt: string | null;
  drawnBy: string | null; // null = automatically when the draft completed
  draws: number;
  weeks: ScheduleWeek[];
}

// Playoffs (YGO-27).

export interface PlayoffSettings {
  enabled: boolean;
  directQualifiers: number | null; // the top N go straight into the bracket
  playInTeams: number | null; // null/0 = none, 2 = one match for one spot, 4 = NBA style (two spots)
  bestOf: number | null; // null = the regular season's
  playInBestOf: number | null; // null = the playoffs'
}

// A play-in or playoff match. a / b are null until known; aFrom / bFrom say where they'll come from.
export interface BracketMatch {
  id: string;
  stage: "PLAY_IN" | "PLAYOFFS";
  round: number;
  label: string;
  seedA: number | null;
  seedB: number | null;
  a: SchedulePlayer | null;
  b: SchedulePlayer | null;
  aFrom: string | null;
  bFrom: string | null;
  bestOf: number;
  readyAt: string | null;
  deadline: string | null; // a week after both players are known
  overdue: boolean;
  result: MatchResult | null;
  winner: SchedulePlayer | null;
  mine: boolean;
  canReport: boolean;
  canConfirm: boolean;
  canEnter: boolean;
  aSource: SlotSource | null; // where a / b come from, as data (YGO-30); null only from an older server
  bSource: SlotSource | null;
}

/** A bracket slot's origin: a seed, or the winner / loser of an earlier match. */
export interface SlotSource {
  kind: "SEED" | "WINNER" | "LOSER";
  seed: number | null;
  matchId: string | null;
}

export interface Playoffs {
  enabled: boolean;
  settings: PlayoffSettings;
  shape: string | null; // "Seeds 1-4 qualify directly; 5-8 play in for 2 spots; …"
  playoffBestOf: number;
  playInBestOf: number;
  started: string | null;
  canStart: boolean;
  canComplete: boolean; // no playoffs: a commissioner completes the season
  why: string | null; // why it can't start / complete now (commissioners)
  seeds: { seed: number; player: SchedulePlayer; playIn: boolean }[]; // frozen once started; a preview before
  matches: BracketMatch[];
  champion: SchedulePlayer | null;
  complete: boolean;
  preview?: BracketMatch[]; // before the start: the bracket the previewed seeds would make (YGO-30)
}

// Trades (YGO-24): one private conversation per pair of players.

export type OfferStatus = "OPEN" | "PENDING_APPROVAL" | "EXECUTED" | "WITHDRAWN" | "CANCELLED" | "REJECTED";

export interface TradeAsset {
  kind: "CARD" | AllowanceKind;
  card: CardRef | null;
  quantity: number;
}

export interface TradeOffer {
  id: string;
  fromMemberId: string;
  from: string;
  toMemberId: string;
  to: string;
  status: OfferStatus;
  counterOf: string | null; // the offer this answers
  at: string;
  fromGives: TradeAsset[];
  toGives: TradeAsset[];
  cancelReason: string | null;
  canAccept: boolean;
  canDecline: boolean;
  canWithdraw: boolean;
  canApprove: boolean;
}

export interface TradeLimits {
  tradesLeft: number | null; // null = unlimited
  claimsLeft: number | null;
}

export interface TradeThread {
  withMemberId: string;
  with: string;
  items: { type: "OFFER" | "MESSAGE"; at: string; offer: TradeOffer | null;
           message: { authorMemberId: string; author: string; typedBy: string | null; body: string; at: string } | null }[];
  mine: TradeLimits;
  theirs: TradeLimits;
}

export interface TradesOverview {
  open: boolean;
  closedReason: string | null;
  week: number;
  budgetWeek: number;
  rules: TransactionRules;
  actors: { memberId: string; name: string; you: boolean }[]; // who you can trade as
  as: string | null;
  mine: TradeLimits | null;
  players: { memberId: string; name: string; you: boolean }[]; // everyone else
  threads: { withMemberId: string; with: string; live: number; awaitingYou: number; lastAt: string; unread?: number }[];
  approvals: TradeOffer[]; // commissioners: accepted trades waiting for a decision
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
  timer: PickTimer | null; // null = no pick timer
  roundCount: number;
  picksPerPlayer: number;
  totalPicks: number;
  draftableCards: number;
  availableCards: number; // draftable cards nobody has picked yet
  seats: { seat: number; displayName: string; teamName: string | null; avatar?: number | null }[];
  board: DraftSlot[];
  picksMade: number;
  onTheClock: OnTheClock | null; // while in progress
  deadline: string | null; // when the pick on the clock runs out (with a timer)
  paused: boolean; // the timer is in its overnight pause right now
  held: boolean; // a commissioner has paused the draft
  heldSince: string | null;
  heldBy: string | null;
  undoable: UndoTarget | null; // what "undo last pick" would reverse (commissioners, while it runs)
  canUnstart: boolean; // a commissioner can take it back to setup (no pick in effect: none made, or all undone)
  timerChangedBy: string | null; // who last changed the timer during the draft
  timerChangedAt: string | null;
  owed: OwedPick[]; // skipped picks not yet made up (everyone's)
  pickable: PickableSlot[]; // slots the viewer may fill now: the one on the clock and/or make-ups
  mySeat: number | null; // the viewer's seat, if they play
  away: boolean | null; // the viewer's away mode (null if they don't play)
  canPick: boolean; // pickable isn't empty
  readiness: { item: string; ok: boolean; detail: string }[]; // only before the start
  startedAt: string | null;
  startedBy: string | null;
  completedAt: string | null;
  serverTime: string; // the server's clock when this was sent, to correct a countdown for the browser's
}

export type TimerExpiry = "WAIT" | "SKIP" | "AUTO_PICK";

export interface UndoTarget {
  overallPick: number;
  seat: number;
  displayName: string;
  card: string | null; // null for a skip
  kind: "PICK" | "AUTO" | "SKIP";
  makeup: boolean;
}

export interface PickTimer {
  seconds: number; // per pick
  onExpiry: TimerExpiry;
  pauseFrom: string | null; // "23:00:00", in the league's zone; with pauseTo
  pauseTo: string | null;
}

export interface OwedPick {
  overallPick: number;
  round: number;
  seat: number;
  displayName: string;
}

export interface PickableSlot extends OwedPick {
  makeup: boolean; // a skipped pick being made up (else the one on the clock)
}

export interface QueuedCard {
  cardId: number;
  name: string;
  cardType: string;
  passcode: number;
  available: boolean; // can still be drafted
}

export interface DraftQueueState {
  away: boolean;
  cards: QueuedCard[];
}

/** A live draft event: a signal that the draft changed (refetch the draft for the details). */
export interface DraftChanged {
  status: DraftState["status"];
  picksMade: number;
  onTheClockSeat: number | null;
  paused: boolean; // a commissioner has paused the draft
}

export interface DraftSlot {
  overallPick: number;
  round: number;
  pickInRound: number;
  seat: number;
  pick: DraftPick | null; // null while the slot is open (or skipped and not made up)
  skipped: boolean; // the timer ran out on this slot (it may since have been made up)
}

export interface DraftPick {
  cardId: number;
  name: string;
  passcode: number;
  madeBy: string | null; // null for an auto-pick
  onBehalf: boolean; // a commissioner picked for the seat's player
  auto: boolean; // picked from the player's queue (timer or away mode)
  madeUp: boolean; // made later, in place of a skipped pick
  madeAt: string;
}

export interface OnTheClock {
  overallPick: number;
  round: number;
  seat: number;
  displayName: string;
  pickInTurn: number; // e.g. pick 1 of 2 in this turn
  picksInTurn: number;
}

// Notifications (league owner only)

export type NotificationEvent = "DRAFT_PICKS" | "DRAFT_ON_THE_CLOCK" | "TRANSACTIONS" | "RESULTS";

export interface MyPings {
  discordUserId: string | null; // the member's Discord user id, for all their leagues
  ping: boolean; // they want to be @mentioned in this league
  leaguePostsOnTheClock: boolean; // pings only come with the league's "on the clock" messages
}

export interface NotificationSettings {
  webhookSet: boolean;
  webhookHint: string | null; // the end of the saved URL ("…AbCd"); the URL itself never comes back
  events: NotificationEvent[];
}
