# CLAUDE.md

General-purpose web app for running Yu-Gi-Oh draft leagues, fantasy-football style: users create accounts, create leagues (a group of people they own), and run seasons in them, configuring card pool and rules per season. Theo's classic-format league is the first tenant, not the spec (see PLANNING.md §0). **Read `PLANNING.md` first**: it covers goals, domain model, rules config, feature backlog and phases.

## Layout
- `backend/`: Spring Boot 4, Java 21, Gradle (Groovy DSL, wrapper committed). Package root `com.ygoleague`.
- `frontend/`: React + TypeScript + Vite. Dev server proxies `/api` to `localhost:8080`.
- `docker-compose.yml`: local Postgres 17 (db/user/pass: `ygoleague`).

## Commands
- DB: `docker compose up -d`
- Backend: `cd backend && ./gradlew bootRun` (`-Pdev` for the dev profile: "log in as…" switcher, seeded test users and Dev League; never use `dev` outside local work)
- Google sign-in: set `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` (env or gitignored `backend/.env`); site admins via `APP_ADMINS=google:<subject>` (see `/api/auth/session` for your identity)
- Backend tests: `cd backend && ./gradlew test` (repository tests use Testcontainers, so Docker must be running)
- Card catalog: `cd backend && ./gradlew syncCatalog` (one-shot YGOPRODeck sync into the local DB; `-Pforce` re-imports an unchanged version)
- Ban lists: `cd backend && ./gradlew importBanLists` (imports `src/main/resources/banlists/**.yaml`; exits 1 on any card name it can't match, so run it after adding or editing a list)
- Card images: cached on first view under `backend/data/card-images` (gitignored). Warm a format's pool as a site admin with `POST /api/admin/card-images/prefetch?tcgCutoff=YYYY-MM-DD&size=small`.
- Frontend: `cd frontend && npm install && npm run dev`
- Frontend typecheck/build: `npm run build`

## Conventions
- Schema changes only via new Flyway migrations in `backend/src/main/resources/db/migration` (never edit an applied one).
- League rules (card pool, copy limits, ban list, draft rounds, transaction limits, tiebreakers) come from season config. Don't hardcode 10 players / 25 rounds / March 2008; our league's values belong in dev seed data and examples only. Migrating the current league from its sheet is not a goal.
- Design wide, build narrow: schema and interfaces must allow every option in PLANNING.md §0 "Scope decisions", but only implement the v1 column unless asked.
- Everything is league-scoped: season data hangs off a `League`. A league is a group of people: one `OWNER` and `MEMBER`s. Commissioner and player are **per-season** roles (the owner commissions every season). Every league-scoped endpoint starts with `LeagueAccess.require(publicId, user, minimumRole)` (non-members get 404, not 403); season endpoints go through `SeasonAccess.member` / `SeasonAccess.commissioner` (403 for members who don't commission that season). Never query league data by id without these.
- State changes (picks, trades, FA, results) are append-only events; ownership is derived from them.
- Keep domain logic (e.g. `draft/SnakeOrder`) free of Spring so it's unit-testable.
- Commit messages start with a ticket prefix in place of Jira: `YGO-<n>: <summary>`. One number per piece of work (shared by its commits); the next number is one more than the highest `YGO-` in `git log`. YGO-1 was the product vision / planning docs.
- Theo is strongest in Java/Spring; explain non-obvious frontend choices briefly.
