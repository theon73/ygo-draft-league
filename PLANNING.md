# YGO Draft League — Planning Doc

Starting reference for building a web app to run the Yu-Gi-Oh classic draft league. It replaces the Google Sheet as the system of record over time. This is a living doc: update it as decisions get made.

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
3. **Ship read-only first.** A league hub that reads data is useful on day one. Write features come in where the sheet hurts most.
4. **The sheet is the escape hatch.** Always support exporting to CSV/Sheets.
5. **League-agnostic underneath, our league on top.** Don't hardcode 10 players, 25 rounds or March 2008. Multi-league support can come later without rewrites.

**Non-goals (for now):** playing duels in-app (Dueling Nexus handles that), real-money anything, native mobile apps.

---

## 2. Stack

| Layer | Choice | Notes |
|---|---|---|
| Frontend | React + TypeScript + Vite | React Router; add TanStack Query for server state |
| Backend | Spring Boot 4 (Java 21), Maven | Spring MVC, Data JPA, Validation, Actuator |
| DB | PostgreSQL 17 | Flyway migrations |
| Auth | Spring Security OAuth2 Login (Google and/or Discord) | Session cookie for the SPA; roles: `COMMISSIONER`, `PLAYER`, `VIEWER` |
| Realtime | SSE to start (draft room, activity feed) | Upgrade to WebSocket/STOMP only if two-way realtime is needed |
| Card data | YGOPRODeck API, imported into our DB | Cache card images locally/CDN; filter by TCG date for the format |
| Notifications | Discord webhook | Picks on the clock, trades, results |
| Hosting | TBD (Fly.io / Railway / Render / own infra) | One container for the backend; static frontend served by the backend or a CDN |

Local dev: `docker compose up -d` for Postgres, `./mvnw spring-boot:run` for the backend (port 8080), `npm run dev` for the frontend (port 5173, proxies `/api` to 8080).

---

## 3. Domain model (first pass)

```
League ──< Season ──< SeasonPlayer >── Player (user account)
              │
              ├── RulesConfig (1:1, JSON or typed columns)
              ├── CardPool ──< PoolCard >── Card (global catalog)
              │                   └── ownership projected from events
              ├── Draft ──< DraftPick
              ├── Transaction (trade | free_agency) ──< TransactionLine
              ├── Week ──< Match ──< Game (replay URL)
              ├── Deck ──< DeckCard          (per player, per week)
              └── Playoff bracket (derived from standings + format)
AuditEvent (everything, append-only)
```

**Key entities**
- **Card**: global catalog (name, type, race, attribute, level, ATK/DEF, archetype, text, TCG release date, image id). Sourced from YGOPRODeck.
- **PoolCard**: a card's membership in a season's pool, plus season flags: `maxCopies`, `banStatus`, `softBanned`, `exclusive`, `unusable` (derived), `requiresOwnership` (e.g. Polymerization). Current owner comes from the ownership projection.
- **DraftPick**: `overallPick`, `round`, `pickInRound`, `seat`, `seasonPlayerId`, `poolCardId`, `pickedAt`, `pickedBy` (for commissioner overrides).
- **Transaction**: `type`, `week`, `initiator`, `counterparty?`, `status` (proposed → accepted/rejected/vetoed → executed), lines of gave/received.
- **Match / Game**: week, players, seeds, games won, winner, replay links; reported by one player and confirmed by the other.
- **Deck**: list of (poolCardId, copies) for main/side/extra. Validated on save; locked per week.

**RulesConfig (what the commissioner can change)**
```yaml
format:
  cardPoolCutoff: 2008-03-01        # TCG release date upper bound
  banList: "TCG 2008-03"
  softBans: [ "<archetype or card names>" ]
  copiesPerRosterCard: 3            # league rule: own 1, play up to 3 (or the legal max)
  ownershipRequiredFor: [ "Polymerization" ]
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
season:
  format: round-robin               # 9 matches each
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
- ⭐ Rules config UI (see RulesConfig above)
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
- Sign in with Google or Discord
- Mobile-friendly
- Public read-only view vs member-only writes
- Multi-league support (only if it becomes a public tool)

### Theo's additions
- _(add here)_

---

## 5. Phases

**Phase 0: Skeleton (this zip)**
- Monorepo, Postgres via compose, Flyway V1, health + players endpoint, Vite app calling the API.
- Snake-order calculator with unit tests (first piece of real domain logic).

**Phase 1: Read-only league hub**
- Import from the current sheet (CSV export of Draft Pool, Draft Log, Match Schedule, Transactions, Rosters).
- Card catalog import from YGOPRODeck (cutoff date filter) and image caching.
- Pages: standings, schedule + replays, rosters with card art, draft board, season archive.
- No auth needed yet; deploy behind an unlisted URL.

**Phase 2: Auth + transactions + deck validator**
- OAuth2 login, mapping accounts to SeasonPlayers, roles.
- Event-sourced ownership; FA claims and trade proposals with validation.
- Deck builder + `.ydk` export.
- Match reporting with confirmation.

**Phase 3: Draft room**
- Live draft over SSE, queue/watchlist, timer, commissioner tools, Discord notifications.
- Mock draft mode.

**Phase 4: Stats, polish, social**
- All-time stats, card stats, recaps, awards, playoff bracket automation.

**Phase 5 (maybe): Make it a public tool**
- Multi-league tenancy, self-serve season wizard, public league pages.

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

## 7. Migration from the sheet

1. Export tabs to CSV: `Draft Pool`, `Draft Log`, `Draft Order`, `Match Schedule`, `Transactions`, `March 2008 TCG Ban List`, `Info`.
2. One-off importer (`backend/.../importer`) that builds Season 1. Ownership is rebuilt by replaying draft picks and then transactions, then checked against the sheet's `Owner` column. Any mismatch is a bug in the import or in the sheet.
3. Import the "Season 2, 2017" sheet as a second season to prove the model handles multiple seasons and formats.
4. Keep the sheet as read-only history once the site is the source of truth.

---

## 8. Open questions

- Hosting target and budget? Own domain?
- Google vs Discord login (or both)? Is everyone in the league on Discord?
- Should decks be hidden from opponents until match time?
- Keep Sheets in sync during the transition (two-way) or do a hard cutover between seasons?
- How much card text and image data to store vs fetch (YGOPRODeck asks that images be downloaded, not hotlinked)?
- Public read-only pages: yes or no?
- Expose league data to Claude via an MCP server so the Claude Helper sheet isn't needed?
