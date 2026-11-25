# YGO Draft League

League hub, draft room, transactions and deck validation for a Yu-Gi-Oh classic draft league.

See [PLANNING.md](PLANNING.md) for the plan.

## Quick start

```bash
docker compose up -d                       # Postgres on :5432

cd backend
./gradlew bootRun                          # API on :8080 (Windows: gradlew.bat bootRun)

cd ../frontend
npm install
npm run dev                                # UI on :5173
```

Check: http://localhost:8080/api/health and http://localhost:5173

Backend tests: `cd backend && ./gradlew test`. Prereqs: JDK 21, Docker, Node 22 LTS (Vite 7 needs 20.19+ or 22.12+). The Gradle wrapper downloads Gradle itself, so no separate install is needed.
