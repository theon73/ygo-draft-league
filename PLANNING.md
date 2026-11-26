# YGO Draft League — Planning Doc

Starting reference for building a web app to run Yu-Gi-Oh draft leagues. The design comes from running our classic-format league on a Google Sheet, but the target is **future leagues and seasons created in the app**, not moving the current one over (see §7). This is a living doc: update it as decisions get made.

---

## 0. Product vision

**A general-purpose platform, not a tool for one league.** Think fantasy football site, but for Yu-Gi-Oh draft leagues:

- Anyone can **create an account**.
- Any user can **create a league** and becomes its **commissioner**.
- The commissioner **invites players** and **configures everything** for each season: card pool (format date, sets, manual adds/removals), ban list, soft bans, copy limits, draft format (seats, rounds, picks per turn, timer), transaction rules, schedule format, tiebreakers and playoff structure.
- A user can be in several leagues, holding a different role in each.

Realistically Theo will be one of the few users, and our league is the main real-world case. That's fine, but the flexibility is the point. **Our league's players, rules and format (10 players, 25 rounds, March 2008, 3 trades + 3 FA a week, etc.) are just one configuration.** They belong in dev seed data and examples, never in code or schema assumptions. When a feature is designed around "how our league does it", the question to ask is: *what is the setting, and what is our league's value for it?*

### Scope decisions (2026-11-25)

**Approach: design wide, build narrow.** The schema and the domain interfaces leave room for every option below. Only what our league uses gets implemented first (marked **v1**); the rest are added later behind the same interfaces, without schema rewrites.

| Area | Decision | v1 |
|---|---|---|
| Cards | Yu-Gi-Oh **TCG only**, any era (Goat, Edison, modern…). No OCG, custom cards or other games. | Cutoff-date pool from YGOPRODeck |
| Ownership | **Configurable per league:** exclusive (one owner per card) or copy-based (a pool card has N copies, owned separately). Exclusive is just N = 1. | Exclusive |
| Draft types | **Snake** (configurable rounds/picks per turn), **auction / salary cap**, **pack / booster** | Snake |
| Packs | Both **real sets** (set lists, rarities, pack odds) and **commissioner-built cubes** | n/a |
| Season continuity | **Fresh each season**: new pool, new draft. League history carries over; rosters don't. | ✓ |
| Drafts per season | One draft, held at the start of the season | ✓ |
| Competition | **Round-robin + playoffs**, **Swiss**, **weekly H2H schedule** (fantasy-style), **ad hoc / ladder** | Round-robin + playoffs |
| Players without accounts | **Placeholders allowed**: the commissioner can add named players, and a user claims the spot later through an invite | ✓ (lets a commissioner set up the season and draft order before everyone signs up) |
| Rule changes | **Versioned.** Mid-season changes (ban list update, new soft ban) take effect from a given week. Past decks and transactions are judged by the rules in effect at that time. | ✓ |
| Free agency | **First-come claims** with configurable limits. No waivers or FAAB bidding. | ✓ |
| Trade assets | Cards, plus **transaction allowances** (e.g. "a card + one of my FA claims this week", so the receiver gets an extra FA move this week and the sender loses one). Legitimizes what our league already does informally. | Nice-to-have |
| Decks | **Configurable per league:** none / optional / required-and-locked per week or match, and hidden or visible to opponents | Optional |
| Visibility | **Members only.** The commissioner can also grant registered non-participants read-only **viewer** access (e.g. a friend who follows the standings). No public pages. | ✓ |

### What this means for the schema

- **Card catalog is global and TCG-specific.** Alongside `card`, store **prints** (`card_set`, `card_print`: set code, rarity, release date) so real-set pack drafts are possible later, and store **dated TCG ban lists** as global data that leagues reference or override.
- **Ownership is quantity-based.** Each pool card carries `copies` (1 = exclusive), and the ownership projection holds (pool card, season player, quantity) with the total never above `copies`. One code path handles both ownership modes.
- **Ownership is season-scoped.** Nothing carries over between seasons, so all pool, roster and transaction data lives under `season`.
- **Draft is polymorphic.** `draft` has a `type` and a type-specific config. Draft events share a common shape (who, what, when) with optional type-specific fields (`price` for auction, `pack_id` / `pick_in_pack` for pack drafts). Strategy classes per type implement "who's on the clock / is this action legal".
- **Competition is polymorphic too.** `match` is generic (stage, round/week number, participants, best-of, result). Schedule generation is a strategy: fixed round-robin, generated each round for Swiss, or none for ladder. Standings are derived from results plus the configured tiebreakers or rating method.
- **`league_member.user_id` is nullable** (placeholder players). Roles: `COMMISSIONER`, `PLAYER`, `VIEWER`. A viewer is an allowlisted account with read-only access. Season participation goes through `season_player → league_member`.
- **Rules are versioned rows, not one JSON blob.** Rows look like `season_rules(season_id, effective_from_week, rules jsonb, schema_version, created_by)`. Validation looks up the version in effect for the week. The JSON is parsed into typed Java records, so the jsonb column stays a storage detail.
- **Transaction lines are typed assets.** Each line has `asset_type` (`CARD`, `FA_ALLOWANCE`, later `TRADE_ALLOWANCE`, …), so allowance trades fit without a new table. The weekly limit is computed as base limit plus allowances received minus allowances given.

---

## 1. Why build this

The sheet works, but it depends on people following the rules and on manual upkeep:

| Pain today | What the site fixes |
|---|---|
| Draftees edit `Owner` directly in a ~2,700-row pool, and one bad edit breaks Rosters | Draft room enforces turn order and pick legality, and writes the log |
| Transaction limits (3 trades + 3 FA/week) and the Pool Check run on formulas and the honor system | Transactions are validated server-side and applied atomically |
| Deck legality is checked by hand | Deck builder is limited to the roster and validated against copy limits, ban list, soft bans and the Polymerization rule |
| Per-player planning sheets are copied from `[DUPLICATE ME]` | Private per-player queue/watchlist |
| Each new season/format means a new sheet, and history is scattered | One data model across seasons, so all-time stats come free |
| Claude Helper sheet built with IMPORTRANGE as a workaround | A read API (and later maybe an MCP server) gives tools clean access |

**Guiding principles**
1. **House rules are config, not code.** Changing a soft ban, copy limit or tiebreak should be a settings change.
2. **Every state change is an event.** Picks, trades, FA moves and results are stored append-only, and current state (ownership) is derived or projected from them. This gives the audit log, undo and history for free.
3. **Follow the season lifecycle.** Build in the order a real season runs (set up the league and pool, then draft, then play) so each phase can be used for real before the next starts.
4. **Exports are the escape hatch.** Always support exporting to CSV/Sheets.
5. **Multi-league from day one.** Every season-scoped row hangs off a `League`, and every permission check is "what is this user's role *in this league*". Don't hardcode 10 players, 25 rounds or March 2008 (see §0).

**Non-goals (for now):** playing duels in-app (Dueling Nexus handles that), real-money anything, native mobile apps, scaling for a large public user base (build for flexibility, not traffic).

---

## 2. Stack

| Layer | Choice | Notes |
|---|---|---|
| Frontend | React + TypeScript + Vite | React Router; add TanStack Query for server state |
| Backend | Spring Boot 4 (Java 21), Gradle | Spring MVC, Data JPA, Validation, Actuator |
| DB | PostgreSQL 17 | Flyway migrations |
| Auth | Spring Security OAuth2 Login (Google and/or Discord) | Session cookie for the SPA; per-league roles: `COMMISSIONER`, `PLAYER`, `VIEWER`. Dev-only login for local work (see Phase 1) |
| Realtime | SSE to start (draft room, activity feed) | Upgrade to WebSocket/STOMP only if two-way realtime is needed |
| Card data | YGOPRODeck API, synced weekly into our DB | See §3 "Card catalog". Images: separate ticket (cache on first view) |
| Notifications | Discord webhook | Picks on the clock, trades, results |
| Hosting | Split: Cloudflare (frontend), Fly.io or Railway (backend), managed Postgres | See "Hosting" below. Budget: a few dollars a month |

**Hosting (decided 2026-11-25).** Split hosting, keeping the Java/Spring + Postgres stack:
- **Frontend:** static Vite build on Cloudflare (Workers static assets or Pages). Free.
- **Backend:** the Spring Boot app as one Docker container on **Fly.io or Railway** (choose at deploy time). Paying a few dollars a month is fine. Size the instance for the JVM, which needs roughly 512 MB.
- **Database:** managed Postgres with a free or cheap tier (Neon or Supabase, or the host's own Postgres). Keep it plain Postgres so the provider can be swapped.
- **Ruled out:** running the backend on Cloudflare Workers. Workers can't run a JVM; it would mean rewriting the backend in TypeScript, or Cloudflare Containers on a paid plan, and D1 is SQLite, not Postgres.
- **Same-origin API:** serve the API under the frontend's domain, either by having Cloudflare route `/api/*` to the backend or with an `api.` subdomain on the same domain. Session cookies then work without cross-site cookie or CORS problems, matching the dev proxy setup.
- Card images: YGOPRODeck asks that images be downloaded, not hotlinked. Plan (decided 2026-11-25): a cached image endpoint that fetches each image the first time it's viewed and serves it from storage behind an interface (local disk in dev, Cloudflare R2 in prod; a classic pool is ~2–3k cards, well inside R2's free tier). Images never go in Postgres. Until that ticket lands, the UI links to the card's YGOPRODeck page. The DB only stores passcodes, which are also the image ids, so this needs no schema change.

Local dev: `docker compose up -d` for Postgres, `./gradlew bootRun` for the backend (port 8080), `npm run dev` for the frontend (port 5173, proxies `/api` to 8080).

---

## 3. Domain model (first pass)

```
User (account) ──< LeagueMember >── League      (role per league: COMMISSIONER | PLAYER | VIEWER)
                                      │  └──< LeagueInvite
League ──< Season ──< SeasonPlayer >── LeagueMember   (member.user nullable = placeholder)
              │
              ├──< SeasonRules (versioned: effective from week N)
              ├── CardPool ──< PoolCard (copies N) >── Card ──< CardPrint >── CardSet   (global catalog)
              │                   └── holdings (member, qty) projected from events
              ├── Draft (type: snake | auction | pack) ──< DraftEvent
              ├── Transaction (trade | free_agency) ──< TransactionLine (asset: card | allowance)
              ├── Stage (regular | playoffs; format: round-robin | swiss | h2h | ladder) ──< Match ──< Game
              ├── Deck ──< DeckCard          (per player, per week or match; policy from rules)
              └── Playoff bracket (derived from standings + format)
BanList ──< BanListEntry   (official dated TCG lists from repo files, or league-owned custom lists)
AuditEvent (everything, append-only)
```

**Key entities**
- **User**: a login identity (OAuth subject, display name, avatar). Global and not tied to any league.
- **League**: name, created by a user. Holds seasons. Visible to members only.
- **Public ids (decided 2026-11-25).** URLs never expose raw database ids and there are no slugs. Each URL-facing entity gets an opaque id encoded from its primary key with [Sqids](https://sqids.org) over `[type tag, id]`, e.g. `/leagues/lIri2her`. The encoding is reversible, so there's no extra column and uniqueness is guaranteed. Decoding rejects non-canonical strings and ids of the wrong type. The alphabet, min length and type tags are **permanent** (changing them breaks every link), and a pinned test guards them. This hides ids and counts but is not security; per-league authorization is. A readable suffix (`/leagues/lIri2her/goat-format`) can be added later, computed from the name and ignored on lookup.
- **LeagueMember**: a membership and role (`COMMISSIONER` / `PLAYER` / `VIEWER`) in one league. Commissioner is a role, not a special user, and a league can have co-commissioners. `user` is nullable, so a commissioner can add placeholder players who claim the spot later. `VIEWER` is read-only access for an account the commissioner has allowlisted.
- **LeagueInvite**: an invite link or code the commissioner hands out to join a league.
- **SeasonPlayer**: a league member taking part in one season (seat, team name). Not every member plays every season.
- **Card**: global catalog (name, type, race, attribute, level/rank/link, ATK/DEF, archetype, current text, TCG/OCG release dates, passcodes). Sourced from YGOPRODeck; see "Card catalog" below.
- **CardTextVersion**: superseded wording with the date it stopped applying, for formats that play older text (e.g. Goat). Text as of date D = earliest version with `validUntil > D`, else current text. Curated; no source yet.
- **CardSet / CardPrint**: TCG sets and the printings of each card in them (set code, rarity). Used for pool building by set and for real-set pack drafts.
- **PoolCard**: a card's membership in a season's pool, plus season flags: `copies` (draftable copies; 1 = exclusive), `maxPlayable`, `softBanned`, `unusable` (derived), `requiresOwnership` (e.g. Polymerization). Ban status comes from the season's ban list plus overrides. Holdings come from the ownership projection.
- **SeasonRules**: a versioned rules document per season (`effectiveFromWeek`, `rules`, `schemaVersion`). Legality checks use the version in effect at the time.
- **DraftEvent**: common fields `sequence`, `seasonPlayerId`, `poolCardId`, `at`, `actedBy` (for commissioner overrides). Type-specific fields: snake → `round`, `pickInRound`, `seat`; auction → `nominatedBy`, `price`; pack → `packId`, `pickInPack`.
- **Transaction**: `type`, `week`, `initiator`, `counterparty?`, `status` (proposed → accepted/rejected/vetoed → executed), lines of gave/received. Each line is an asset: a pool card (with quantity) or a transaction allowance (e.g. 1 FA claim this week).
- **Match / Game**: week, players, seeds, games won, winner, replay links; reported by one player and confirmed by the other.
- **Stage / Match / Game**: a season has one or more stages (regular season, playoffs), each with a format. Matches are generic so that round-robin, Swiss, H2H weeks and ladder all fit.
- **Deck**: list of (poolCardId, copies) for main/side/extra. Validated on save. The submission policy (none / optional / required-and-locked) and visibility to opponents come from the rules.

**Card catalog (built in YGO-3, 2026-11-25).**
- **Scope:** TCG only. Cards with no TCG date (OCG-only, ~500) and Speed Duel Skill Cards are skipped. Tokens are kept, and pool building can filter them. YGOPRODeck occasionally omits the TCG date of a real TCG card (found: Change of Heart). If such a card has TCG prints, its date falls back to its earliest dated set, which is approximate but keeps the card.
- **Identity:** YGOPRODeck's `id` is **not** stable. It's just the first artwork listed, and the order differs between responses (Dark Magician comes back as 46986414 or 46986420). Cards match on `konami_id` (unique; missing for ~12 tokens/unreleased cards), falling back to any shared passcode. A card stores every artwork's passcode.
- **Sync:** weekly scheduled job plus `./gradlew syncCatalog` (`-Pforce` to re-import). Skipped when YGOPRODeck's `checkDBVer` version is unchanged. One transaction with an advisory lock. Unchanged cards are detected by a content hash over the record with prices, popularity and ordering normalized away. Nothing is ever deleted. Each run is logged in `catalog_sync`. No HTTP trigger yet (see admin access below).
- **Sets:** the set name is the key, because set codes are prefixes several products share (both 2013 tin waves are `CT10`).
- **Size:** ~14k cards, ~45k prints, ~40 MB including the raw JSON. A full sync takes ~6 s.

**Ban lists (built in YGO-4, 2026-11-25).**
- **Revisions, not edits.** A ban list is an identity plus append-only revisions. Season rules reference a specific *revision*, so editing a list never changes what a past season played under. Saving identical content is a no-op.
- **Official lists:** dated TCG lists are YAML files in `backend/src/main/resources/banlists/tcg/<effective-date>.yaml`. Card names are quoted, as on Yugipedia, with an optional passcode to disambiguate. They're imported on startup, after every catalog sync, and via `./gradlew importBanLists` (exits 1 on any unmatched name, with suggestions). Each file is all-or-nothing. A changed file becomes a new revision.
- **Matching** is case-insensitive and normalizes curly quotes, dashes, accents and spacing. The import warns when a listed card was released after the list's date (a hint of a wrong match or a wrong date).
- **Shipped so far:** March 2008 (105 cards) and March 2017 (176 cards), supplied by Theo, with effective dates (2008-03-01, 2017-03-31) confirmed by Theo.
- **Custom lists:** league-owned, with Forbidden / Limited / Semi-Limited statuses. A custom list starts from scratch or as a copy of any revision the league can see (official, or its own). Leagues can't see each other's lists. These replace the earlier idea of per-season overrides.
- YGOPRODeck's `banlist_info` only carries *current* TCG/OCG/Goat statuses, so it isn't a historical source.

**Site admin access (decided 2026-11-25).** Global actions (catalog sync, official ban list edits) need a platform-level admin, separate from league roles. Admin status comes from an **allowlist of login identities in server config/env**, never a DB flag and never settable through any API, so a bug in user editing can't escalate anyone. Admin actions are idempotent or versioned, and audit-logged. Until an admin UI exists, these run only on a schedule, from the CLI, or via repo files.

**RulesConfig (what the commissioner can change).** The values below are *our league's* settings, given as an example. Every field is per season and versioned (see SeasonRules). New leagues start from sensible defaults or a clone of a previous season.
```yaml
format:
  cardPoolCutoff: 2008-03-01        # TCG release date upper bound
  banList: "TCG 2008-03"
  softBans: [ "<archetype or card names>" ]
  ownershipRequiredFor: [ "Polymerization" ]
ownership:
  mode: exclusive                   # exclusive | copies
  copiesPerPoolCard: 1              # copies mode: default draftable copies per card
  playableCopiesPerOwned: 3         # league rule: own 1, play up to 3 (or the legal max)
decks:
  submission: optional              # none | optional | required
  lockPer: week                     # week | match
  visibleToOpponents: true
draft:
  type: snake
  seats: 10
  rounds:                           # 10 × 1 pick, then 15 × 2 picks = 400 picks
    - { from: 1,  to: 10, picksPerTurn: 1 }
    - { from: 11, to: 25, picksPerTurn: 2 }
  pickTimerSeconds: null
transactions:
  tradesPerWeek: 3
  freeAgencyPerWeek: 3
  tradeDeadlineWeek: null
  commissionerApproval: false
  allowanceTrading: true            # FA/trade allowances can be traded as assets
season:
  format: round-robin               # round-robin | swiss | h2h | ladder; 9 matches each here
  matchBestOf: 3
  tiebreakers: [ wins, gameDiff, gamesWon, headToHead ]
playoffs:
  byes: [1, 2]
  locked: [1, 2, 3, 4]
  playIn: { forSeed5: [5, 6], forSeed6: { winnerOf: [7, 8], vs: loserOf5v6 } }
  rounds: [ { pairs: [[3,6],[4,5]] }, semis, finals ]
```

---

## 4. Feature backlog

⭐ = first-pass priority. Phases are in §5.

### Draft
- ⭐ Live draft room: snake order, 1-pick vs 2-pick rounds, "on the clock" indicator
- ⭐ Blocks illegal picks (owned / unusable / soft-banned) and writes the draft log automatically
- Pick timer with auto-skip or auto-pick from queue
- Personal pick queue/watchlist (private), replacing duplicated planning sheets
- Draft board view (grid of round × player)
- Pool browser with filters (type, attribute, archetype, level, ATK/DEF, available only) and search
- Pick notifications (Discord webhook / email / push)
- Mock draft / practice mode
- Pause, undo, commissioner override

### Rosters & cards
- ⭐ Roster pages with card images and hover previews
- Card detail page: owner, ownership history, decks using it
- Archetype ownership map
- Free-agent pool view with filters

### Transactions
- ⭐ Trade proposals: propose → accept/reject → executes on accept
- ⭐ Free agency claims validated against pool, ownership and weekly limits
- Transaction feed / league activity log
- Trade deadline and transaction windows
- Commissioner veto/approval queue (optional)
- Waiver priority (optional)

### Decks
- ⭐ Deck builder limited to own roster; validates copies, ban list, soft bans, ownership-required cards
- Export to `.ydk` for Dueling Nexus; import `.ydk` with a legality check
- Weekly deck submission/lock, optionally hidden until the match
- Deck history per player across weeks/seasons

### Matches & season
- ⭐ Schedule, results entry, auto standings with configured tiebreakers
- Replay links per game
- Playoff bracket view that updates from standings
- Two-sided match reporting (report + confirm)
- Weekly matchup preview: opponent roster, recent decks, H2H

### History & stats
- ⭐ Season archive: standings, champion, draft board per season
- All-time records: titles, win %, cross-season H2H
- Card stats: most drafted, most traded, appearance in winning decks
- Draft value analysis: pick position vs usage

### League setup / commissioner
- ⭐ Create a league (creator becomes commissioner); invite players by link/code
- ⭐ Rules config UI (see RulesConfig above)
- ⭐ Card pool builder: format cutoff date, include/exclude sets, manual add/remove, per-card flags
- Season creation wizard: clone the last season, pick a format date, generate the pool from YGOPRODeck
- Player management, seat-order randomizer
- Audit log of every change and who made it
- Export to Sheets/CSV

### Social / fun
- Team names and deck nicknames on profiles
- Weekly recap page / auto-generated summary
- Discord bot/webhook for results, trades, picks
- Trophy case and awards (MVP, best trade, biggest upset)

### Platform
- ⭐ Accounts: sign in with Google or Discord
- ⭐ Multi-league: "My leagues" dashboard, per-league roles, league switcher
- Mobile-friendly
- Members-only leagues, plus a commissioner-managed allowlist of read-only viewer accounts

### Theo's additions
- _(add here)_

---

## 5. Phases

**Phase 0: Skeleton (this zip)**
- Monorepo, Postgres via compose, Flyway V1, health + players endpoint, Vite app calling the API.
- Snake-order calculator with unit tests (first piece of real domain logic).

> Everything is created in the app; there's no import step to lean on. Accounts therefore come first, because every write needs an actor and every read needs a league role.

**Phase 1: Accounts, leagues, card catalog**
- Migrations for the core tenancy model: `app_user`, `league`, `league_member` (nullable user, role), `league_invite`, `season` scoped to a league, `season_player`, versioned `season_rules`. Delete the V1 player seed in a new migration (done in YGO-2); dev sample data goes in a dev-only seed instead.
- OAuth2 login, "My leagues", create a league (creator becomes commissioner), invite links, placeholder members, viewer allowlist.
- **Local auth (decided 2026-11-25).** There are three layers:
  - **Dev login, `dev` profile only.** `POST /api/dev/login` (by user id or name) creates the same session and principal as a real OAuth login, resolving the user through `user_identity` with `provider = 'dev'`. Code after login is identical in dev and prod, and no permission checks are skipped. A dev-only seed (a separate Flyway location loaded only by the `dev` profile) creates test users and a sample league. When the backend reports dev mode, the frontend shows a "log in as…" switcher.
  - **Automated tests** use Spring Security test support (mock principal / `oauth2Login()` in MockMvc), not the dev endpoint.
  - **Real OAuth locally**, via separate dev OAuth apps with `localhost` redirect URIs, to test the actual login flow occasionally.
  - **Prod guardrails:** the controller is `@Profile("dev")` (absent from prod, not just disabled). `dev` is never a default profile. Startup fails if `dev` is active alongside a prod marker (`prod` profile or a non-localhost DB URL). A test asserts `/api/dev/login` returns 404 without the `dev` profile.
  - A fake OIDC provider (e.g. Keycloak in compose) is only worth adding if the real flow starts breaking in ways the dev login hides.
- **YGO-3 (done):** card catalog sync from YGOPRODeck (cards, sets, prints, card text versions, sync log).
- **YGO-4 (done):** ban lists (official YAML files + importer, league-owned custom lists with revisions).
- **YGO-5 (done):** temporary read-only Explorer page (`/explore`, `/api/explore/*`) for the catalog and official ban lists, plus generic Home copy. Unauthenticated, so it exposes global data only; replace it with the real pool builder in Phase 2.
- **YGO-6:** login, the dev login, and the site admin allowlist.
- Later in Phase 1: create a league, invites, placeholder members, viewer allowlist, "My leagues"; cached card image endpoint.

**Phase 2: Season setup**
- Season creation: rules config UI (typed, versioned), seat order / randomizer.
- Card pool builder: cutoff date, include/exclude sets, manual add/remove, per-card flags (soft ban, requires ownership, copies), pool browser with filters.

**Phase 3: Draft room**
- Snake draft with pick legality, draft board, and the draft log written as events.
- Live updates over SSE, queue/watchlist, timer, pause/undo/commissioner override, Discord notifications.
- Mock draft mode.

**Phase 4: Season play**
- Event-sourced ownership; rosters with card art.
- FA claims and trade proposals with validation, including allowance trades.
- Round-robin schedule generation, match reporting with confirmation, replay links, standings with tiebreakers, playoff bracket.
- Deck builder + `.ydk` export/import, deck submission policy per league.

**Phase 5: Stats, polish, social**
- Season archive, all-time stats, card stats, recaps, awards.

**Phase 6: Deferred options + public polish**
- The deferred options from §0: copy-based ownership, auction and pack drafts (real sets + cubes), Swiss / H2H / ladder competition.
- Onboarding for new commissioners, rules presets (e.g. "Goat format", "Edison"), and hardening for strangers using it (rate limits, abuse handling).

---

## 6. API sketch

```
GET    /api/seasons/{id}                       season + rules
GET    /api/seasons/{id}/pool?filters          pool browser (paged)
GET    /api/seasons/{id}/rosters               all rosters
GET    /api/seasons/{id}/standings
GET    /api/seasons/{id}/schedule
GET    /api/seasons/{id}/draft                 draft state + board
POST   /api/seasons/{id}/draft/picks           make a pick (validated)
GET    /api/seasons/{id}/draft/stream          SSE: picks, on-the-clock
POST   /api/seasons/{id}/transactions          FA claim or trade proposal
POST   /api/transactions/{id}/accept|reject
POST   /api/seasons/{id}/decks                 save/validate a deck
GET    /api/decks/{id}/ydk                     export
POST   /api/matches/{id}/report | /confirm
GET    /api/seasons/{id}/export.csv
```

---

## 7. Importing an existing league (optional, not planned)

**Not a goal.** The current league runs to completion on its sheet, and the app is for future seasons. If development finishes while that season is still going, an import *might* be attempted. Nothing should be designed around it, but the event model makes it possible if we want it:

1. Export the sheet tabs to CSV (`Draft Pool`, `Draft Log`, `Draft Order`, `Match Schedule`, `Transactions`, ban list, `Info`).
2. A one-off importer creates the league, placeholder members and season, then replays draft picks and transactions as events. The resulting ownership is checked against the sheet's `Owner` column.

The sheet remains useful as a **requirements reference**: it shows what a real season needs.

---

## 8. Open questions

- Own domain? (Hosting itself is decided; see §2.) Fly.io vs Railway, and which Postgres provider, are decided at first deploy.
- Google vs Discord login (or both)? Is everyone in the league on Discord? *(Needed early now: accounts are Phase 1.)*
- A source for historical (pre-errata) card text, so `card_text_version` doesn't have to be curated by hand?
- Where to get real-set pack data (rarity odds, pack composition) for pack drafts? YGOPRODeck has set lists and rarities but not pull rates.
- Expose league data to Claude via an MCP server so the Claude Helper sheet isn't needed?
