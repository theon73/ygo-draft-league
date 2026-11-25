# CLAUDE.md

Web app for running a Yu-Gi-Oh classic-format draft league. **Read `PLANNING.md` first**: it covers goals, domain model, rules config, feature backlog and phases.

## Layout
- `backend/`: Spring Boot 4, Java 21, Maven. Package root `com.ygoleague`.
- `frontend/`: React + TypeScript + Vite. Dev server proxies `/api` to `localhost:8080`.
- `docker-compose.yml`: local Postgres 17 (db/user/pass: `ygoleague`).

## Commands
- DB: `docker compose up -d`
- Backend: `cd backend && ./mvnw spring-boot:run` (generate the wrapper once with `mvn -N wrapper:wrapper` if it's missing)
- Backend tests: `cd backend && ./mvnw test`
- Frontend: `cd frontend && npm install && npm run dev`
- Frontend typecheck/build: `npm run build`

## Conventions
- Schema changes only via new Flyway migrations in `backend/src/main/resources/db/migration` (never edit an applied one).
- League rules (copy limits, ban list, draft rounds, transaction limits, tiebreakers) come from season config. Don't hardcode 10 players / 25 rounds / March 2008.
- State changes (picks, trades, FA, results) are append-only events; ownership is derived from them.
- Keep domain logic (e.g. `draft/SnakeOrder`) free of Spring so it's unit-testable.
- Theo is strongest in Java/Spring; explain non-obvious frontend choices briefly.
