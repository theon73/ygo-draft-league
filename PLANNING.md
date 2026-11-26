# YGO Draft League — Planning Doc

Starting reference for building a web app to run Yu-Gi-Oh draft leagues. The design comes from running our classic-format league on a Google Sheet, but the target is **future leagues and seasons created in the app**, not moving the current one over (see §7). This is a living doc: update it as decisions get made.

---

## 0. Product vision

**A general-purpose platform, not a tool for one league.** Think fantasy football site, but for Yu-Gi-Oh draft leagues:

- Anyone can **create an account**.
- Any user can **create a league** (a group of people) and becomes its **owner**.
- The owner **invites people** and creates seasons. Each season has its own **commissioners** (the owner always, plus anyone they add) who **configure everything** for that season: card pool (format date, sets, manual adds/removals), ban list, soft bans, copy limits, draft format (seats, rounds, picks per turn, timer), transaction rules, schedule format, tiebreakers and playoff structure.
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
| Players without accounts | **Placeholders allowed**: the owner can add named members, and a user claims the spot later through an invite | ✓ (lets a commissioner set up the season and draft order before everyone signs up) |
| Rule changes | **Versioned.** Mid-season changes (ban list update, new soft ban) take effect from a given week. Past transactions and results are judged by the rules in effect at that time. | ✓ |
| Free agency | **First-come claims** with configurable limits. No waivers or FAAB bidding. | ✓ |
| Trade assets | Cards, plus **transaction allowances** (e.g. "a card + one of my FA claims this week", so the receiver gets an extra FA move this week and the sender loses one). Legitimizes what our league already does informally. | Nice-to-have |
| Decks (decided 2026-11-25) | **Out of scope.** Deck building, deck lists and duels happen on **Dueling Nexus**; this app is the league's organizer (membership, pool, draft, rosters, transactions, schedule, results, standings). Optional, stateless bridges later: export a roster for Dueling Nexus, and paste a deck to check it against the roster and rules. No stored decks or deck names. | None |
| Casual game modes (noted 2026-11-25) | **Later.** Modes outside league play, run standalone (invite by link, no league) or inside a league without affecting its standings. Ideas so far: a pick-one-of-three deck draft (40 picks of 1 from 3), and a lobby snake draft on a sampled pool of 40 × players. In both, the picks are the deck, exported to Dueling Nexus, so no deck builder is needed. See §4 "Other game modes"; names to come. | — |
| Visibility | **Members only.** Any league member can follow every season, playing or not (e.g. a friend who follows the standings is just a member who isn't in a season). No public pages. | ✓ |
| Roles (decided 2026-11-25, YGO-11) | **The league is the group; roles are per season.** League: one **owner** (the creator; manages members, invites, settings, seasons) and **members**. Season: **commissioners** (the owner always, implicitly, plus members chosen per season; need an account) and **players** (its participants; any member, placeholders included). One person can be both. A member who isn't playing a season just follows it. | ✓ |

### What this means for the schema

- **Card catalog is global and TCG-specific.** Alongside `card`, store **prints** (`card_set`, `card_print`: set code, rarity, release date) so real-set pack drafts are possible later, and store **dated TCG ban lists** as global data that leagues reference or override.
- **Ownership is quantity-based.** Each pool card carries `copies` (1 = exclusive), and the ownership projection holds (pool card, season player, quantity) with the total never above `copies`. One code path handles both ownership modes.
- **Ownership is season-scoped.** Nothing carries over between seasons, so all pool, roster and transaction data lives under `season`.
- **Draft is polymorphic.** `draft` has a `type` and a type-specific config. Draft events share a common shape (who, what, when) with optional type-specific fields (`price` for auction, `pack_id` / `pick_in_pack` for pack drafts). Strategy classes per type implement "who's on the clock / is this action legal".
- **Competition is polymorphic too.** `match` is generic (stage, round/week number, participants, best-of, result). Schedule generation is a strategy: fixed round-robin, generated each round for Swiss, or none for ladder. Standings are derived from results plus the configured tiebreakers or rating method.
- **`league_member.user_id` is nullable** (placeholder members). League roles: `OWNER` (exactly one) and `MEMBER`. Season roles: `season_commissioner` (plus the owner, implicitly) and `season_player`. Season participation goes through `season_player → league_member`.
- **Rules are versioned rows, not one JSON blob.** Rows look like `season_rules(season_id, effective_from_week, rules jsonb, schema_version, created_by)`. Validation looks up the version in effect for the week. The JSON is parsed into typed Java records, so the jsonb column stays a storage detail.
- **Transaction lines are typed assets.** Each line has `asset_type` (`CARD`, `FA_ALLOWANCE`, later `TRADE_ALLOWANCE`, …), so allowance trades fit without a new table. The weekly limit is computed as base limit plus allowances received minus allowances given.

---

## 1. Why build this

The sheet works, but it depends on people following the rules and on manual upkeep:

| Pain today | What the site fixes |
|---|---|
| Draftees edit `Owner` directly in a ~2,700-row pool, and one bad edit breaks Rosters | Draft room enforces turn order and pick legality, and writes the log |
| Transaction limits (3 trades + 3 FA/week) and the Pool Check run on formulas and the honor system | Transactions are validated server-side and applied atomically |
| Deck legality is checked by hand | (Optional, later) paste a Dueling Nexus deck to check it against your roster, copy limits, ban list, soft bans and the Polymerization rule; nothing is stored |
| Per-player planning sheets are copied from `[DUPLICATE ME]` | Private per-player queue/watchlist |
| Each new season/format means a new sheet, and history is scattered | One data model across seasons, so all-time stats come free |
| Claude Helper sheet built with IMPORTRANGE as a workaround | A read API (and later maybe an MCP server) gives tools clean access |

**Guiding principles**
1. **House rules are config, not code.** Changing a soft ban, copy limit or tiebreak should be a settings change.
2. **Every state change is an event.** Picks, trades, FA moves and results are stored append-only, and current state (ownership) is derived or projected from them. This gives the audit log, undo and history for free.
3. **Follow the season lifecycle.** Build in the order a real season runs (set up the league and pool, then draft, then play) so each phase can be used for real before the next starts.
4. **Exports are the escape hatch.** Always support exporting to CSV/Sheets.
5. **Multi-league from day one.** Every season-scoped row hangs off a `League`, and every permission check is "what is this user's role *in this league*". Don't hardcode 10 players, 25 rounds or March 2008 (see §0).

**Non-goals (for now):** playing duels or building and storing decks in-app (Dueling Nexus handles both), real-money anything, native mobile apps, scaling for a large public user base (build for flexibility, not traffic).

---

## 2. Stack

| Layer | Choice | Notes |
|---|---|---|
| Frontend | React + TypeScript + Vite | React Router; add TanStack Query for server state |
| Backend | Spring Boot 4 (Java 21), Gradle | Spring MVC, Data JPA, Validation, Actuator |
| DB | PostgreSQL 17 | Flyway migrations |
| Auth | Spring Security OAuth2 Login (Google; decided 2026-11-25) | Session cookie for the SPA; league roles `OWNER`/`MEMBER`, season roles commissioner/player. Dev-only login for local work (see Phase 1) |
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
- **Card images (YGO-10; sized 2026-11-25).** YGOPRODeck asks that images be downloaded, not hotlinked. We cache each image in our own storage behind an interface (local disk in dev, Cloudflare R2 in prod) and serve it from there. Images never go in Postgres: passcodes, already in the catalog, are the image ids.
  - **Measured sizes** (15 random cards per variant): full card (`cards`, ~420×615) **158 KB** avg (134–195); thumbnail (`cards_small`, ~170×245) **26 KB** (23–30); art crop (`cards_cropped`) 128 KB (100–229). The catalog has 14,085 cards / **14,255 artworks**; a March 2008 pool is 2,717 cards / 2,823 artworks.
  - **Storage is bounded by the catalog, not by users or leagues** (one global cache): ~0.5 GB for a 2008 pool (small + full), **~2.5 GB ceiling for the whole catalog** in small + full, ~4.3 GB if crops are added. Crops aren't stored until a feature needs them. Under R2's free 10 GB for good.
  - **Upstream load is bounded too**: each image is fetched from YGOPRODeck once, ever. The real constraint is the *rate* of first fetches (their API bans IPs above 20 req/s; images sit behind Cloudflare, so stay at ~5/s). So: one throttled fetcher for the whole app, de-duplicated concurrent requests, thumbnails prefetched in the background when a season's pool is finalized (~2.8k images ≈ 10 min), full images on demand, and a placeholder while an image is loading.
  - **Bandwidth is the only thing that scales with users.** Assume ~100 MB of new images per active user per month (a 400-pick draft board in thumbnails is ~10 MB; browser caching absorbs repeats). Served from R2 behind Cloudflare's CDN it's ~$0 at any size, because R2 has no egress fees and the edge cache keeps R2 reads inside the free tier. Proxied through the backend it would cost ~$2–5/month at 1k users, $20–50 at 10k and $200–500 at 100k (Fly ~$0.02/GB, Railway ~$0.05/GB, list prices at the time), plus backend capacity. Re-check prices at deploy.
  - **Design rules that keep the public path a config change:** images are addressed as `{variant}/{passcode}.jpg` everywhere (local disk, R2 keys, URLs); they're immutable (served with a one-year cache); and the frontend builds image URLs from a configurable base (`VITE_CARD_IMAGE_BASE`, default `/api/card-images`). Going public means pointing that base at R2 on our own domain (e.g. `cards.<domain>`) behind Cloudflare's cache and pre-warming the whole catalog once (~28k images ≈ 1.5 h at 5/s). Only the backend fills the cache; it serves images itself only in dev or as a fallback.
  - **Before going public (not cost, but must check):** card art is Konami's copyright and YGOPRODeck doesn't own it. Fan tools commonly use it, but a large public app (and anything monetized) should get this looked at. Also check YGOPRODeck's current terms (attribution, heavy use) and Cloudflare's terms for serving images.
  - **At scale, hosting cost is driven by the backend and Postgres, not images:** SSE connections in the draft room, connection pooling, a paid Postgres tier and session-table growth. Plan that with Phase 3.

Local dev: `docker compose up -d` for Postgres, `./gradlew bootRun` for the backend (port 8080), `npm run dev` for the frontend (port 5173, proxies `/api` to 8080).

---

## 3. Domain model (first pass)

```
User (account) ──< LeagueMember >── League      (league role: OWNER | MEMBER; season roles via SeasonCommissioner / SeasonPlayer)
                                      │  └──< LeagueInvite
League ──< Season ──< SeasonPlayer >── LeagueMember   (member.user nullable = placeholder)
              │
              ├──< SeasonRules (versioned: effective from week N)
              ├── CardPool ──< PoolCard (copies N) >── Card ──< CardPrint >── CardSet   (global catalog)
              │                   └── holdings (member, qty) projected from events
              ├── Draft (type: snake | auction | pack) ──< DraftEvent
              ├── Transaction (trade | free_agency) ──< TransactionLine (asset: card | allowance)
              ├── Stage (regular | playoffs; format: round-robin | swiss | h2h | ladder) ──< Match ──< Game
              └── Playoff bracket (derived from standings + format)
BanList ──< BanListEntry   (official dated TCG lists from repo files, or league-owned custom lists)
AuditEvent (everything, append-only)
```

**Key entities**
- **User**: a login identity (OAuth subject, display name, avatar). Global and not tied to any league.
- **League**: name, created by a user. Holds seasons. Visible to members only.
- **Public ids (decided 2026-11-25).** URLs never expose raw database ids and there are no slugs. Each URL-facing entity gets an opaque id encoded from its primary key with [Sqids](https://sqids.org) over `[type tag, id]`, e.g. `/leagues/lIri2her`. The encoding is reversible, so there's no extra column and uniqueness is guaranteed. Decoding rejects non-canonical strings and ids of the wrong type. The alphabet, min length and type tags are **permanent** (changing them breaks every link), and a pinned test guards them. This hides ids and counts but is not security; per-league authorization is. A readable suffix (`/leagues/lIri2her/goat-format`) can be added later, computed from the name and ignored on lookup.
- **LeagueMember**: a membership in one league's group: `OWNER` (exactly one, the creator) or `MEMBER`. Commissioning and playing are per season (`season_commissioner`, `season_player`); the owner commissions every season. `user` is nullable, so the owner can add placeholder members who claim the spot later.
- **LeagueInvite**: an invite link the owner hands out to join the league (or to claim a placeholder seat).
- **SeasonPlayer**: a league member taking part in one season (seat, team name). Not every member plays every season.
- **Card**: global catalog (name, type, race, attribute, level/rank/link, ATK/DEF, archetype, current text, TCG/OCG release dates, passcodes). Sourced from YGOPRODeck; see "Card catalog" below.
- **CardTextVersion**: superseded wording with the date it stopped applying, for formats that play older text (e.g. Goat). Text as of date D = earliest version with `validUntil > D`, else current text. Curated; no source yet.
- **CardSet / CardPrint**: TCG sets and the printings of each card in them (set code, rarity). Used for pool building by set and for real-set pack drafts.
- **PoolCard**: a card's membership in a season's pool, plus season flags: `copies` (draftable copies; 1 = exclusive), `maxPlayable`, `softBanned`, `unusable` (derived), `requiresOwnership` (e.g. Polymerization). Ban status comes from the season's ban list plus overrides. Holdings come from the ownership projection.
- **SeasonRules**: a versioned rules document per season (`effectiveFromWeek`, `rules`, `schemaVersion`). Legality checks use the version in effect at the time.
- **DraftEvent**: common fields `sequence`, `seasonPlayerId`, `poolCardId`, `at`, `actedBy` (for commissioner overrides). Type-specific fields: snake → `round`, `pickInRound`, `seat`; auction → `nominatedBy`, `price`; pack → `packId`, `pickInPack`.
- **Transaction**: `type`, `week`, `initiator`, `counterparty?`, `status` (proposed → accepted/rejected/vetoed → executed), lines of gave/received. Each line is an asset: a pool card (with quantity) or a transaction allowance (e.g. 1 FA claim this week). A counteroffer is a new trade proposal with `counterOf` pointing at the one it answers (status *countered*). **TradeComment**: (trade, author, text, at). **TradeListing** (trading block): (season player, cards offered, what they'd like, note, status open/closed); offers on it are trade proposals with `listingId` set. **Visibility (decided 2026-11-25, following fantasy-site convention):** trade proposals, counteroffers and their comments are private to the players involved and the season's commissioners; trading block listings and the offers and comments on them are public to the league. Completed trades show up in the league's transaction feed.
- **Match / Game**: week, players, seeds, games won, winner, replay links; reported by one player and confirmed by the other.
- **Stage / Match / Game**: a season has one or more stages (regular season, playoffs), each with a format. Matches are generic so that round-robin, Swiss, H2H weeks and ladder all fit.

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

**Settings catalog (enumerated 2026-11-25).** Everything a league should eventually be able to configure, so each feature knows its settings before it's built. Settings are added with the feature that uses them, not up front. *Scope*: **League** = a column or table on the league, changes apply from now on. **Season** = a field in the versioned `season_rules` document (see SeasonRules), so mid-season changes take effect from a given week and the past stays judged by the old rules. *Status*: ✓ built, **v1** = needed for our league's first season in the app, *later* = behind the same interfaces (§0).

*League*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Name | text, ≤ 60 chars | League | ✓ YGO-8 |
| Description / house-rules blurb | markdown | League | later |
| Visibility | members only; any member follows every season (no public pages, §0) | League | fixed by design ✓ |
| Season commissioners | per season: the league owner (always) plus chosen members with accounts; they run that season | Season | ✓ YGO-11 |
| League ownership | one owner per league; transferring ownership or adding co-owners | League | owner ✓; transfer/co-owners later |
| Placeholder members | named seats without accounts, claimed via invite | League | ✓ YGO-9 |
| Invites | open join links and placeholder claim links; expiry and max uses per invite; created by the owner (letting season commissioners invite too, later); league-wide defaults later | League | ✓ YGO-9/11; rest later |
| Member display names | per-league name, editable by the member themselves (and by commissioners). Matters especially after claiming a placeholder, which keeps the seat's name the commissioner chose (YGO-9); the claimer should be able to rename it. Unique within the league; past seasons show the name as it is now. | League | later |
| Time zone | IANA zone; defines week boundaries, deadlines and draft times | League | ✓ YGO-11 |
| Week start | the day and local time each league week starts (e.g. Monday 00:00, or Tuesday 20:30); weeks follow daylight saving (`calendar.WeekBoundary`). A season takes the league's week start as its own when it begins, so later changes never re-bucket a running season | League (season copy at start) | ✓ YGO-11; season copy in YGO-12 |
| Notifications | Discord webhook URL; which events (picks, trades, FA, results) | League | later, Phase 3–4 |
| New-season defaults | clone the last season's rules, or start from a preset ("Goat", "Edison") | League | later, Phase 2 / 6 |
| Archive / delete / transfer ownership | | League | later |

*Season setup*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Name, status | setup → drafting → active → complete | Season | ✓ schema; Phase 2 |
| Participants | which members play this season; team names | Season | ✓ YGO-11 |
| Seat order | manual or random (✓ YGO-11, draws recorded); from last season's standings (e.g. reverse) later | Season | ✓ manual/random; standings-based later |
| Calendar | start date and number of regular-season weeks (stored as a list of weeks); week boundary copied from the league when the season starts | Season | ✓ YGO-12; the copy at season start comes with Phase 3 |
| Off weeks | whole-league weeks with no matches (holidays, mass travel), placed anywhere in the schedule. To decide when built: whether an off week takes a week number (week 5 is off, matches resume in week 6) or the numbering skips it; whether weekly trade/FA limits apply during it; how it shifts match deadlines and the playoff start. The schedule, "this week's matchups" and standings must all show it | Season | later, Phase 4 (keep in mind when designing the calendar in YGO-12) |

*Card pool*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Card game | TCG only | — | fixed (§0) |
| Release window | TCG release date ≤ cutoff (optionally also ≥ a start date) | Season | ✓ cutoff in the rules (YGO-12); applied when the pool is generated (YGO-13); start date later |
| Sets | include/exclude specific sets | Season | later (needs a set picker; the cutoff covers our league) |
| Manual adds/removes | individual cards | Season | ✓ YGO-13 |
| Exception list | hand-picked cards added on top of the dated pool (e.g. just past the cutoff), each with an optional reason; addable mid-season as a dated change effective from week N | Season | ✓ YGO-13 |
| Mid-season additions reach rosters by | free agency, commissioner assignment, or a supplemental draft | Season | open; Phase 4 |
| Card kinds | tokens are always excluded from the dated pool (Skill Cards are never imported); other kinds later if needed | Season | ✓ YGO-13 |
| Pool size | the whole generated pool, or a random sample of N cards per player (e.g. 40 × players) for a small, fast draft; sampled once and recorded, so the pool is fixed and reproducible | Season | later (with the casual modes) |
| Card text era | current text, or text as of the cutoff date (`card_text_version`, e.g. Goat errata) | Season | later |
| Per-card flags | soft-banned, requires ownership (per card or per archetype); copy limit from the ban list with a per-card league override; copies (copies mode) later | Season | ✓ YGO-13 (flags) |

*Ban list and restrictions*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Ban list | a specific revision of an official or league-owned list | Season | ✓ lists (YGO-4); ✓ selection (YGO-12) |
| Soft bans | cards or archetypes that can't be drafted or played | Season | v1, Phase 2 |
| Ownership-required cards | e.g. Polymerization must be on your roster to be played | Season | v1, Phase 2 |

*Ownership and rosters*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Ownership mode | exclusive, or copies (N owners per pool card) | Season | v1 exclusive; copies later |
| Copies per pool card | default N (copies mode) | Season | later |
| Playable copies per owned card | e.g. own 1, play up to 3 (or the legal max) | Season | v1, Phase 4 |
| Roster size | min / max cards held (affects FA claims and drops) | Season | later |

*Draft*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Draft type | snake, auction / salary cap, pack (real sets or cube) | Season | v1 snake; others later |
| Rounds | segments of rounds with picks per turn (e.g. 10 × 1, then 15 × 2) | Season | ✓ YGO-15 |
| Order | snake or linear; first-round order from seat order | Season | ✓ YGO-15 |
| Start time | planned start (in the league time zone), shown to the league; a commissioner starts the draft (automatic start later, if wanted) | Season | ✓ YGO-15 |
| Forbidden cards draftable | whether cards Forbidden on the season's ban list can be drafted (off by default in exclusive ownership) | Season | ✓ YGO-15 |
| Pick timer | none or N seconds; on expiry: wait, skip, or auto-pick from the player's queue; optional overnight pause window | Season | later, Phase 3 |
| Commissioner controls | pause, undo, pick on someone's behalf | Season | pick on someone's behalf ✓ YGO-16; pause and undo v1, YGO-19 |
| Auction settings | budget, minimum bid, nomination order, bid timer | Season | later |
| Pack settings | source (sets / cube), packs per player, cards per pack, pass direction, rarity odds | Season | later |
| Mock drafts | allowed or not | League | later |

*Transactions*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Trades per week | number or unlimited | Season | v1, Phase 4 |
| Free-agent claims per week | number or unlimited; first-come only (no waivers / FAAB, §0) | Season | v1, Phase 4 |
| Trade deadline | week number or none | Season | v1, Phase 4 |
| Transaction windows | when in the week trades and claims are open | Season | later |
| Commissioner approval | trades need approval, can be vetoed within N hours, or neither | Season | v1 (off), Phase 4 |
| Allowance trading | FA claims / trades can be traded as assets | Season | later (nice-to-have) |
| Counteroffers and trade comments | answer a proposal with a changed one; comment thread on each offer | Season | v1, Phase 4 (with trades) |
| Trading block | players list cards they want to move; others reply with offers or comments | Season | later, Phase 4+ |
| Trade visibility | proposals, counteroffers and comments private to the players involved and the season's commissioners; trading block public to the league; completed trades in the league feed | — | decided 2026-11-25 (fixed by design for now) |
| Dropped cards | return to free agency immediately or after a delay | Season | later |

*Dueling Nexus bridge (optional; decks themselves live on Dueling Nexus)*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Roster export | download a player's roster in a format Dueling Nexus imports (`.ydk` or its card-list text) | — | later |
| Deck check | paste a deck to check it against the roster, ban list, soft bans and ownership-required cards; stateless, nothing stored | Season | later |
| Deck rules for the check | copy limit (normally 3, lowered by ban list status; "own 1, play up to N"), main/extra/side size limits by format | Season | later, with the deck check |

*Matches and standings*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Competition format | round-robin (each pair meets N times; weeks derived), Swiss (N rounds), weekly H2H, ladder | Season | ✓ round-robin (YGO-12); others later |
| Rounds per week | how many round-robin rounds are played each week (fixed at 1 for now) | Season | later |
| Best-of | per match (e.g. Bo3); can differ for playoffs | Season | v1, Phase 4 |
| Draws | allowed or not; points per win / draw / loss | Season | later |
| Reporting | one player reports and the other confirms; auto-confirm after N hours; commissioner-only | Season | v1 report + confirm, Phase 4 |
| Replay links | required or optional | Season | later |
| Forfeits | deadline to play a week's match; what happens if missed | Season | later |
| Match scheduling | players agree a match time in the app; the expected time is shown to the league for spectators; calendar feed and reminders optional | Season | later |
| Late / backfilled matches | a past week's match can still be played and reported after its deadline instead of becoming a forfeit: an automatic grace period (N days), a commissioner-granted extension for one match, or both. A late result counts for its original week, and standings recompute from it. Example: a player can't play until the day after the deadline, so the commissioner extends that one match. | Season | later, Phase 4 (design the match deadline with this in mind) |
| Tiebreakers | ordered list (wins, game diff, games won, head-to-head, …) | Season | v1, Phase 4 |

*Playoffs*

| Setting | Options | Scope | Status / built with |
|---|---|---|---|
| Enabled, qualifiers | on/off; number of seeds | Season | v1, Phase 4 |
| Bracket | byes, play-ins, round pairings (see the RulesConfig example) | Season | v1, Phase 4 |
| Best-of per round | e.g. Bo3, finals Bo5 | Season | later |
| Third-place match | on/off | Season | later |

Not league settings: site admins and the catalog sync (platform level, YGO-6), draft queues (always private), and the card game (TCG only).

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
- Card detail page: owner, ownership history
- Archetype ownership map
- Free-agent pool view with filters

### Transactions
- ⭐ Trade proposals: propose → accept/reject → executes on accept
- **Counteroffers** (requested 2026-11-25): the recipient answers a proposal with a changed one instead of a plain reject. A counter is a new proposal linked to the one it answers (the original is marked *countered*), so a negotiation reads as a chain and only the latest offer can be accepted
- **Comments on trade offers**: a short thread on each proposal for the people involved ("I'd do it if you add X"), kept with the trade's history
- **Trading block** (requested 2026-11-25): a board where a player lists cards they're looking to move ("I have this card I want to give away"), optionally with what they'd like back. Other players answer a listing with offers (ordinary trade proposals linked to it) or comments. Closing the listing, or accepting one of its offers, takes it off the board
- ⭐ Free agency claims validated against pool, ownership and weekly limits
- Transaction feed / league activity log
- Trade deadline and transaction windows
- Commissioner veto/approval queue (optional)
- Waiver priority (optional)

### Dueling Nexus bridge (optional)
- Export a roster for import into Dueling Nexus (`.ydk` or card-list text)
- Paste a deck to check it against the roster and the season's rules (nothing stored)

### Matches & season
- ⭐ Schedule, results entry, auto standings with configured tiebreakers
- Replay links per game
- Playoff bracket view that updates from standings
- Two-sided match reporting (report + confirm)
- Weekly matchup preview: opponent roster, H2H
- **Match scheduling** (requested 2026-11-25, later): the two players of a match agree a time in the app (propose / accept times, or each share availability), and the agreed or expected time is shown to the league so others can watch. Optionally a league calendar of upcoming matches, a calendar (.ics) feed, and Discord reminders. Times are shown in the league's zone and each viewer's own

### History & stats
- ⭐ Season archive: standings, champion, draft board per season
- All-time records: titles, win %, cross-season H2H
- Card stats: most drafted, most traded
- Draft value analysis: pick position vs usage

### League setup / commissioner
- ⭐ Create a league (creator becomes owner); invite people by link
- ⭐ Rules config UI (see RulesConfig above)
- ⭐ Card pool builder: format cutoff date, include/exclude sets, manual add/remove, per-card flags
- Season creation wizard: clone the last season, pick a format date, generate the pool from YGOPRODeck
- Player management, seat-order randomizer
- Audit log of every change and who made it
- Export to Sheets/CSV

### Social / fun
- Team names on profiles (a player's name for their season entry, e.g. "Insect Armageddon")
- Weekly recap page / auto-generated summary
- Discord bot/webhook for results, trades, picks
- Trophy case and awards (MVP, best trade, biggest upset)

### Platform
- ⭐ Accounts: sign in with Google or Discord
- ⭐ Multi-league: "My leagues" dashboard, per-season roles, league switcher
- Mobile-friendly
- Members-only leagues; members who aren't playing a season follow it
- **CI pipeline (needed at some point, at the latest before the first deploy).** GitHub Actions on every PR: backend `./gradlew test` (Testcontainers needs Docker, which GitHub's Ubuntu runners have) and frontend `npm run build`. Later it can also build the backend image and deploy on merge to `main`. Tests need no secrets: they never load `backend/.env` and use a fake Google client.

### Other game modes (requested 2026-11-25)
Casual modes that run on their own, without a league, or inside one without counting toward its standings.

- **Pick-one-of-three deck draft** (the format people know from arena-style card games): each player makes **40 picks in a row, choosing 1 of 3 cards each time**, and the 40 picks are their deck. They then play one or more matches (on Dueling Nexus) against others who drafted the same way, for fun.
  - **Who:** anyone can start a session and invite people by link (no league needed), or a league member starts one inside a league. Each player drafts on their own, at their own pace; nobody waits for anyone.
  - **Card source:** a TCG cutoff date (the dated pool), a season's pool, or a cube, with the ban list applied. Reuses the pool builder (YGO-13) and the catalog.
  - **Afterwards:** export the deck for Dueling Nexus (the same bridge as roster export); optionally record wins and losses for the session, kept apart from any league standings.
  - **To decide when built:** how the 3 options are chosen (purely random, or weighted by rarity or card type so decks stay playable); duplicates (whether a card can be offered again); Extra Deck cards (picked within the 40, offered in separate picks, or excluded); whether everyone sees the same sequence of options (fairer to compare) or their own; the deck size (40 by default, configurable).
  - **Model sketch:** `DraftSession` (owner, card source, settings, optional league), `SessionPlayer`, and an append-only pick log (`pick number, the 3 offered card ids, the chosen one`), so a run can always be replayed and checked.
- **Lobby snake draft on a sampled pool** (requested 2026-11-25): a lobby of n players gets a pool of **40 × n cards** drawn from a card source, and they snake-draft it until everyone has 40, which is their deck. Standalone (a lobby you invite people to) or in league format.
  - **Reuses** the Phase 3 snake draft (order, picks per turn, timer, draft board, pick log) and the pool builder; the only new piece is sampling the pool. In league format it's simply a season whose pool is a sample instead of the whole dated pool (see "Pool size" in the settings catalog).
  - **To decide when built:** how the pool is sampled (uniformly from the source, or balanced by card type or rarity so every deck is playable); whether the sample can contain copies; whether to add a margin (e.g. 45 × n so the last picks aren't forced); Extra Deck cards (inside the 40 or a separate allotment); and whether the lobby drafts live (together, with a timer) or asynchronously.
- *Names for these modes to be chosen later.*

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
- OAuth2 login, "My leagues", create a league (creator becomes owner), invite links, placeholder members.
- **Local auth (decided 2026-11-25).** There are three layers:
  - **Dev login, `dev` profile only.** `POST /api/dev/login` (by user id or name) creates the same session and principal as a real OAuth login, resolving the user through `user_identity` with `provider = 'dev'`. Code after login is identical in dev and prod, and no permission checks are skipped. A dev-only seed creates test users and a sample league. *(Built as a Java runner, `auth.DevSeed`, rather than a dev-only Flyway location: Flyway refuses to start when the DB holds a migration it can't see, which would break switching between dev and non-dev runs on one local DB.)* When the backend reports dev mode, the frontend shows a "log in as…" switcher.
  - **Automated tests** use Spring Security test support (mock principal / `oauth2Login()` in MockMvc), not the dev endpoint.
  - **Real OAuth locally**, via separate dev OAuth apps with `localhost` redirect URIs, to test the actual login flow occasionally.
  - **Prod guardrails:** the controller is `@Profile("dev")` (absent from prod, not just disabled). `dev` is never a default profile. Startup fails if `dev` is active alongside a prod marker (`prod` profile or a non-localhost DB URL). A test asserts `/api/dev/login` returns 404 without the `dev` profile.
  - A fake OIDC provider (e.g. Keycloak in compose) is only worth adding if the real flow starts breaking in ways the dev login hides.
- **YGO-3 (done):** card catalog sync from YGOPRODeck (cards, sets, prints, card text versions, sync log).
- **YGO-4 (done):** ban lists (official YAML files + importer, league-owned custom lists with revisions).
- **YGO-5 (done):** temporary read-only Explorer page (`/explore`, `/api/explore/*`) for the catalog and official ban lists, plus generic Home copy. Unauthenticated, so it exposes global data only; replace it with the real pool builder in Phase 2.
- **YGO-6 (done):** login, the dev login, and the site admin allowlist.
  - **Google only.** Enabled when `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` are set (env or gitignored `backend/.env`). The OAuth endpoints are under `/api` (`/api/oauth2/authorization/google`, callback `/api/login/oauth2/code/google`), so the dev proxy and the planned `/api/*` production routing both cover them. A first login creates the user. The display name is taken from Google once; the avatar follows Google. **Two OAuth clients:** the dev client (localhost redirect, consent screen in Testing, secret in `backend/.env`) exists now. Production gets its own client, ideally in its own Cloud project, at first deploy, so secrets rotate independently and the live app never accepts localhost redirects. Google subjects are the same across clients, so `APP_ADMINS` entries carry over. **Behind a proxy:** Spring builds the OAuth redirect URI from the request's host, so in production set `server.forward-headers-strategy: framework` (and make sure the proxy sends `X-Forwarded-Host`/`-Proto`), or the callback will point at the backend's internal address. Locally the Vite proxy keeps the browser's `Host` (`changeOrigin: false`) for the same reason.
  - **Principal:** `auth.CurrentUser` (our user id, display name, the identity used, admin flag) is stored in the HTTP session for both Google and dev logins. Nothing Google-issued is kept after login: the access token is discarded and the principal holds only our own data.
- **YGO-7 (done): persistent sessions.** Sessions live in Postgres (Spring Session JDBC, `V6__http_sessions.sql`), so sign-ins survive restarts and deploys and work across instances. 30-day idle timeout (refreshed on use), and a 90-day absolute cap via the cookie's `Max-Age`. The `SESSION` cookie is `HttpOnly` and `SameSite=Lax`. `Secure` comes from `SESSION_COOKIE_SECURE` and **must be `true` in production**. Session data is Java-serialized; a deploy that changes a stored class turns old sessions into "signed out" (not an error), so users just sign in again.
  - **API behaviour:** signed-out calls get 401, never a redirect. CSRF uses Spring Security's SPA mode (`XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`). `GET /api/auth/session` returns the user, the configured providers and whether dev login is on. Logout is `POST /api/auth/logout`.
  - **Admins:** `APP_ADMINS` lists `provider:subject` identities (shown to each user by `/api/auth/session`). `/api/admin/**` needs the role. First admin action: `POST /api/admin/catalog/sync` (a button on the Explorer).
  - **Dev:** `./gradlew bootRun -Pdev` seeds Dev League: Alice (owner, site admin), Bob, Carol, Dave (members) and a placeholder, Erin.
- **YGO-8 (done):** leagues and per-league access. `GET/POST /api/leagues` ("My leagues", create with a name of up to 60 characters; the creator becomes commissioner) and `GET /api/leagues/{publicId}` (overview with members; user ids never exposed). `league.LeagueAccess.require(publicId, user, minimumRole)` is the single authorization check: **non-members, unknown and malformed ids all get 404** (a league's existence isn't revealed), members below the required role get 403. Roles are ordered commissioner > player > viewer. Frontend: `/leagues` and `/leagues/:id` with league tabs (overview, plus placeholders for standings, rosters, draft and transactions). *(Roles superseded in YGO-11: league roles are now owner/member, commissioner and player are per season, invites carry no role, and the role-change endpoint is gone.)*
- **YGO-9 (done):** invites, placeholders, viewers and commissioner management. Commissioners create **open invite links** (player or viewer; expiry 1 to 90 days or never; optional max uses) and **claim links** for placeholder seats (single-use; the claimer keeps the seat's name and role). Codes are 128-bit random, base64url. Previewing a link works signed out (`GET /api/invites/{code}`); accepting needs a login, and Google sign-in returns to the invite page. Joining with a name already used in the league gets a number ("Yugi 2"). Commissioners add placeholders, change roles, revoke invites and remove members. Rules: at least one commissioner always remains, placeholders can't be commissioners, and anyone who has played in a season can't be removed (league history). The viewer allowlist is viewer invites plus removal. Every membership change locks the league row first, so concurrent changes can't interleave. *(Roles superseded in YGO-11: league roles are now owner/member, commissioner and player are per season, invites carry no role, and the role-change endpoint is gone.)*
- **YGO-10 (done):** cached card images. `GET /api/card-images/{small|full}/{passcode}.jpg` serves from the local store (`backend/data/card-images`, gitignored), fetching from YGOPRODeck on first request: catalog passcodes only, one app-wide throttle (5/s), concurrent requests share one fetch, upstream failures return 503 + `Retry-After` and aren't cached. One-year immutable caching, and a separate cookie-free, session-free security chain so responses stay CDN-cacheable. Admin warm-up: `POST /api/admin/card-images/prefetch?tcgCutoff=YYYY-MM-DD&size=small`. Frontend `CardImage` builds URLs from `VITE_CARD_IMAGE_BASE` and retries while an image is being fetched; the Explorer card page shows the card and its alternate artworks. A startup guard (`image.ImageStoreGuard`) refuses `app.images.store=local` under the `prod` profile, because a container's disk is wiped on every deploy and isn't shared between instances; production can't start until the R2 store exists. R2 storage and pool-finalization prefetch come with deploy / Phase 2.

**Phase 2: Season setup**
- **League vs season (decided 2026-11-25).** A league is the lasting group (members, roles, invites, time zone, history); a season is one run of play inside it (participants, rules, pool, draft, rosters, matches). Kept separate rather than "one league per season" so membership carries over, cross-season history works, and next season can start from last season's rules. The UI keeps the layer out of the way: a league opens on its current season (newest not complete), with season tabs there and past seasons under Seasons.
- **The pool can change, including mid-season (decided 2026-11-25).** Real use: our league retroactively added cards just past the cutoff, to help people play certain decks or to boost losing rosters. So a season's pool is **the dated pool** (generated from the rules: TCG cutoff, sets, card kinds) **plus an exception list** of hand-picked cards, each with an optional reason. Pool changes are append-only, dated events (who, when, card, effective from week N, reason), so the past is always judged by the pool as it was: a week-3 deck never depends on a card added in week 6. Exception cards are marked as such in the pool browser.
  - *Open (Phase 4):* how a mid-season addition reaches rosters: it becomes a free agent (first-come claims), the commissioner assigns it to a specific roster, or a small supplemental draft. Possibly configurable.
  - *Decided (YGO-13):* a card can only be removed while nobody owns it; to take an owned card out of play, soft-ban it instead.
- **YGO-11 (done):** seasons, per-season roles, participants, seat order, league time zone and week start. **Roles moved from the league to the season** (V9 migrates existing data: each league's creator becomes its owner, other commissioners become commissioners of that league's existing seasons, everyone else a member; tested in `V9SeasonRolesMigrationTest`). The owner creates seasons (unique name per league); a season's commissioners (the owner plus anyone chosen, accounts only) choose its commissioners and participants (any member, placeholders included), set seats by hand or draw them at random. Every draw is kept in `season_seat_draw` and shown to the league (who, when, result). Seats are always 1..n; changing participants keeps everyone else's relative order and appends newcomers. Participants and seats are fixed once the season leaves setup; team names stay editable (by a commissioner or the player). League time zone: an IANA region zone, set from the creator's browser on creation (UTC if unknown) and editable in league settings. The Setup page shows one Members table with a per-member season role (—, Player, Commissioner, Commissioner & player): a dropdown for the season's commissioners, set in one step via `PUT /api/leagues/{id}/seasons/{id}/members/{memberId}`, and plain text for everyone else. League week start: a weekday and local time (default Monday 00:00), editable in settings with a preview of the current week. Frontend: league tabs (current season, Seasons, Members, Settings), season tabs (Setup, plus placeholders for Draft, Rosters, Standings, Transactions). League and season pages share their loaded data through React contexts (outlet context only reaches one route level, which blanked the season setup page).
- **YGO-12 (done):** the season rules document. Typed records (`season.rules.SeasonRulesDocument`, schema version 1) stored as JSON in `season_rules`, with only Phase 2's sections built: **competition** (format; round-robin only so far, set as how many times each pair meets: 1 = single, 2 = double; Swiss, weekly H2H and ladder later), **calendar** (start date; the season's weeks as a list, all regular for now, so off weeks fit later; a week-start snapshot that stays empty until the season begins and the league's setting applies) and **format** (TCG cutoff; the exact ban list revision, official or the league's own, which later revisions of the list don't change). **Every save is a new version** (append-only, not an overwrite): during setup versions take effect from week 0, so the latest applies; once the season has started a change takes effect from a chosen week, never before the current week or the latest change, and the calendar can't change. Identical saves are no-ops. Week 1 begins at the league's first week start on or after the start date (`calendar.SeasonCalendar`, DST-aware). **A round-robin's weeks are derived, not set:** rounds per cycle are n - 1 for an even player count or n for an odd one (one bye per round), one round per week, so weeks = cycles × rounds (10 players, single round-robin = 9 weeks, 9 matches each; `competition.RoundRobin`). During setup they follow the player list; the competition format, like the calendar, can't change once the season has started. `GET/PUT /api/leagues/{id}/seasons/{id}/rules`, `GET /api/leagues/{id}/ban-lists`. `season.SeasonAccess` is now the single season-level authorization check. Frontend: a Rules tab (form for commissioners, read-only for members) with the week layout and version history.
  - *Phase 3:* when the draft moves a season out of setup, copy the league's week start into the calendar snapshot and write out the derived week list.
  - *Later:* rounds per week (a league that plays faster than one round a week).
- **YGO-13 (done):** the card pool (V10). `pool_card` holds the current pool (season, card, source DATED or EXCEPTION, copies = 1 for exclusive ownership, flags, reason, and `added_in_week`/`removed_in_week` so each card's time in the pool is known per week); `pool_change` is the append-only log (generated, added, removed, flags; who, when, effective week, reason). **Generate** (setup only) takes every card released in the TCG on or before the rules' cutoff, tokens excluded (about 2.7k cards and 0.2 s for March 2008); regenerating after a cutoff change adds and drops dated cards and keeps exceptions and hand removals. **Exceptions**: any catalog card added by hand with an optional reason, before or during the season. **Removal** only while nobody owns the card (`PoolOwnership`, which says "nobody" until the Phase 3 draft creates ownership); otherwise soft-ban. **Flags**: soft-banned, requires ownership, per card or for a whole archetype at once. **Copy limits follow the season's ban list** (Forbidden 0, Limited 1, Semi-Limited 2, otherwise 3); a league can override a card's limit in either direction as a house rule (e.g. a Limited card at 2), shown as such, and clear it to go back to the list. The pool shows each card's ban status, with a filter and counts. Mid-season changes take effect from a chosen week, never earlier than the current one. **Thumbnails are prefetched automatically** after generating and when a card is added (no separate "ready" step; the draft starting is the real lock). Frontend: a Pool tab (summary, generate, filters and search, flag controls, adding exceptions, archetype shortcut, history).
  - *Later:* including or excluding specific sets when generating (needs a set picker); copies above 1 (copies ownership mode).
- **YGO-14 (done):** the pool browser. **Monster tags** (V11): a generated, GIN-indexed `card.monster_tags` column taken from YGOPRODeck's type line in the stored raw record (Normal, Effect, Ritual, Fusion, Synchro, Xyz, Link, Pendulum, Tuner, Flip, Union, Gemini, Spirit, Toon), so syncs keep it current with no code change. **Filters** on `GET …/pool/cards` (bound onto `PoolQuery`) are multi-select: within a filter choices mean *any of*, different filters must *all* match (Fusion or Synchro + Effect = effect Fusions and Synchros). Name search and **card text search** (a substring of the card's text, any case, any kind; pendulum and monster effects alike), kind (monster, spell, trap), deck (Main, Extra), summon type (Ritual, Fusion, Synchro, Xyz, Link), Normal (no effect, which includes non-effect Fusions) or Effect, abilities (Tuner, Flip, Union, Gemini, Spirit, Toon, Pendulum), attribute, monster type, spell type, trap type, archetype, ban status (including not on the list); separate Level, Rank, Link rating, ATK, DEF and Pendulum scale ranges; sort (name, newest, ATK, DEF, level) and page size. Kind-specific filters imply their kind (a spell type shows spells, an ATK range shows monsters), and each kind keeps its own subtype choice. Every filter is a fixed SQL fragment with bound values. `GET …/pool/facets` lists the values present for the dropdowns. Frontend: a Grid / Table toggle (players start on the grid, commissioners on the table; remembered per browser), toggle chips for short lists, a searchable checkbox list for monster types and archetypes, ranges that apply on Enter or blur, all filters in the URL (Back and shared links work), tiles with the stats line, badges, hover/tap preview, and unusable cards greyed out.
  - *Phase 4:* an "available only" filter once ownership exists.
- **Phase 2 is complete** with YGO-14.

**Phase 3: Draft room**
- **Decisions (2026-11-26):**
  - **Pace: support both fast (live, everyone online; our league's current draft) and slow (picks over days, more flexible for other leagues).** The pick timer is a setting in minutes or hours; live updates are essential, not optional.
  - **Forbidden cards are left out of the draft by default** in exclusive ownership (they're unplayable, so picking one wastes a pick); a setting allows drafting them.
  - **The season becomes active automatically when the last pick is made.** Week 1 still starts on the calendar's date.
  - **The draft log is authoritative and permanent.** Picks are append-only events that nothing edits (trades and free agency are separate events), and when the draft completes a frozen **draft result** (the final board: who picked what, when, and who made each pick) is stored with the season for the archive.
  - **The draft room is the season's Draft tab:** before the start, settings, a board preview, a readiness checklist and Start draft; during, the live board and picking; after, the final board.
- **YGO-15 (done):** draft setup. A **draft section in the season rules** (versioned like the rest; edited on the Draft tab during setup): snake or linear order (`SnakeOrder` gained linear), contiguous round blocks with 1 to 5 picks per turn (at most 100 rounds), a planned start time entered in the league's zone (informational; a commissioner starts the draft), and whether Forbidden cards can be drafted (off by default). The Draft tab is now the draft room: settings, a **board preview** (rounds × seats with pick numbers), a **readiness checklist** (at least two seated players, rounds set, a pool, enough draftable cards for every pick, the calendar's start date and weeks), and **Start draft**. Starting moves the season to drafting (players, seats and pool generation fixed), **locks the calendar** (the league's week start and a round-robin's derived weeks written into the season as a new rules version) and records the settings and seat order as they were at the start in the new `draft` table (V12), which also has room for the frozen result written at completion (YGO-16). `GET …/draft`, `PUT …/draft/settings`, `POST …/draft/start`.
  - *YGO-19:* a way to undo starting (back to setup) while no picks have been made; until then starting is final.
- **YGO-16 (done):** making picks. **`draft_pick`** (V13) is the append-only log: `sequence` orders it and `overall_pick` is the slot a row fills, kept apart (and a card not unique per season) so YGO-19's undo and YGO-18's skips and auto-picks can be added as more rows without a schema change; `kind` is only `PICK` for now. The draft runs from the config frozen at the start, never the live rules; the slot on the clock follows from the log (`DraftProgress`, Spring-free, also says "pick 1 of 2 this turn"). **Who can pick:** the seat's player, or a **commissioner for any seat** (pulled forward from YGO-19, since placeholder seats have no account to pick with); `made_by` records who clicked and the board shows picks made on someone's behalf. **Legality:** in the pool, not soft-banned, not Forbidden unless allowed, a copy nobody holds; the request carries the `overallPick` the picker saw, so a stale click is refused rather than landing on the next slot. Every pick takes a **per-season lock** (`select … for update` on the draft row). **Ownership** is the new `card_holding` view (season, card, season player, quantity), only picks for now; Phase 4 redefines it to add trades and free agency. `PoolOwnership` reads it, so owned cards can't leave the pool. The pool list shows **who owns each card** and gains **"available only"**. The **last pick completes the draft**: the final board is frozen into `draft.result` and the season becomes active. Frontend: the Draft tab shows who's on the clock (highlighted when it's you), the board filling in with card names (hover for the card), and the available cards with the pool's filters and a Draft button for whoever may pick. Until YGO-17 it **re-fetches every 4 s** while the draft runs (skipped while the browser tab is hidden). `POST …/draft/picks`.
- **YGO-17 (done):** live updates over SSE. `GET …/draft/events` is a server-sent-event stream per season (members only, checked on every connect). An event is a **signal, not the board**: `draft` with the status, picks made and the seat on the clock, sent **after the change commits** (start and every pick); clients refetch `GET …/draft`, so per-viewer fields and access stay in one place. Every connect gets the current state first, so the browser's automatic reconnect never misses anything. A comment line every 20 s keeps proxies from closing idle streams and drops dead ones; a stream ends after 30 minutes and the browser reconnects. Access is checked in a short transaction and the stream holds no database connection (open-in-view is off). **Streams live in the backend's memory, which is right for one instance; with several, picks must be fanned out across them (e.g. Postgres LISTEN/NOTIFY).** Frontend: the season layout holds the connection (during the draft on every season tab; during setup only on the Draft tab, to see it start), and pages refetch when it signals; the 4 s polling is gone. **"You're on the clock" alert:** a banner on every other season tab with a link to the draft, and "Your pick ·" in front of the page title so a background browser tab shows it. (Away from the app: Discord, YGO-20.)
- **YGO-18:** private pick queue; optional pick timer (minutes or hours; on expiry wait, skip or auto-pick from the queue; optional overnight pause in the league's zone).
- **YGO-19:** commissioner controls: pause and resume, undo the last pick (as its own event), undo starting while no picks are made. (Picking on someone's behalf came with YGO-16.)
- **YGO-20:** Discord webhook notifications (on the clock, picks).
- Later: mock draft mode; auction and pack drafts (Phase 6); the casual game modes.

**Phase 4: Season play**
- Event-sourced ownership; rosters with card art.
- FA claims and trade proposals with validation, including allowance trades.
- Round-robin schedule generation, match reporting with confirmation, replay links, standings with tiebreakers, playoff bracket.
- Optional Dueling Nexus bridge: roster export and a stateless deck check.

**Phase 5: Stats, polish, social**
- Season archive, all-time stats, card stats, recaps, awards.

**Phase 6: Deferred options + public polish**
- The deferred options from §0: copy-based ownership, auction and pack drafts (real sets + cubes), Swiss / H2H / ladder competition.
- Onboarding for new commissioners, rules presets (e.g. "Goat format", "Edison"), and hardening for strangers using it (rate limits, abuse handling).
- Other game modes (§4 "Other game modes"): the pick-one-of-three deck draft and the lobby snake draft on a sampled pool. Either can come earlier if wanted: the first needs only the catalog and a card source (YGO-13's pool); the second also needs the Phase 3 snake draft.

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
GET    /api/seasons/{id}/rosters/{player}/ydk  roster export for Dueling Nexus (optional)
POST   /api/seasons/{id}/deck-check            check a pasted deck, nothing stored (optional)
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
- A source for historical (pre-errata) card text, so `card_text_version` doesn't have to be curated by hand?
- Where to get real-set pack data (rarity odds, pack composition) for pack drafts? YGOPRODeck has set lists and rarities but not pull rates.
- Expose league data to Claude via an MCP server so the Claude Helper sheet isn't needed?
