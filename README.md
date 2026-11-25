# YGO Draft League

League hub, draft room, transactions and deck validation for a Yu-Gi-Oh classic draft league.

See [PLANNING.md](PLANNING.md) for the plan.

## Quick start

```bash
docker compose up -d                       # Postgres on :5432

cd backend
mvn -N wrapper:wrapper                     # first time only, creates ./mvnw
./mvnw spring-boot:run                     # API on :8080

cd ../frontend
npm install
npm run dev                                # UI on :5173
```

Check: http://localhost:8080/api/health and http://localhost:5173
